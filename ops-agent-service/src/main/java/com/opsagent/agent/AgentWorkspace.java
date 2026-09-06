package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens.Context;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 事件工作区只读聚合；工单可见性不提升运行、审批和隔离现场的权限。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class AgentWorkspace {
    private final AgentStore store;
    private final AgentClients clients;

    AgentWorkspace(AgentStore store, AgentClients clients) {
        this.store = store;
        this.clients = clients;
    }

    ObjectNode view(long ticketId) {
        Context initial = clients.current(SecurityUsers.current(), "ticket-workspace");
        // Read ACL must succeed before touching run counts, approvals or platform evidence.
        JsonNode ticket =
                clients.call(
                        "ticket",
                        "/internal/agent/tickets/" + ticketId + "/workspace-context",
                        "GET",
                        null,
                        initial);
        return aggregate(ticketId, ticket, initial);
    }

    private ObjectNode aggregate(long ticketId, JsonNode ticket, Context initial) {
        String incident = ticket.path("incidentId").asText("");
        String target = ticket.path("affectedCiCode").asText("");
        boolean all = initial.roles().contains("ADMIN");
        boolean bound =
                !incident.isBlank()
                        && "ISOLATED".equals(ticket.path("environment").asText())
                        && AgentTargets.supported(target);
        boolean operational =
                bound && (all || ticket.path("ownerActorId").asLong() == initial.userId());
        ObjectNode view =
                AgentJson.object()
                        .put("schemaVersion", 1)
                        .put("ticketId", ticketId)
                        .put("incidentId", blank(incident))
                        .put("targetCode", blank(target))
                        .put("generatedAt", Instant.now().toString());
        view.set("limits", AgentJson.object().put("runs", 6).put("changes", 12));
        view.set(
                "access",
                AgentJson.object()
                        .put("runScope", all ? "ADMIN" : "OWNER")
                        .put("operationalEvidence", operational)
                        .put(
                                "notice",
                                all
                                        ? "管理员范围内的关联运行与事件证据。"
                                        : "只展示当前账户的关联运行与审批；公开工单不公开其他账户的现场和执行记录。"));
        ArrayNode facts = view.putArray("facts");
        ArrayNode gaps = view.putArray("gaps");
        ArrayNode changes = view.putArray("changes");
        ArrayNode sources = view.putArray("sources");
        fact(
                facts,
                "ticket-status",
                "工单状态",
                ticket.path("status").asText(),
                "TICKET",
                null,
                "TICKET");
        fact(facts, "target", "关联服务", target, "TICKET", null, "TICKET");
        source(sources, "ticket", "AVAILABLE", "已通过工单读取权限校验。");
        List<AgentStore.Run> runs =
                store.workspaceRuns(initial.userId(), all, ticketId, blank(incident));
        view.put("runTotal", store.count(initial.userId(), all, ticketId, blank(incident)));
        ArrayNode summaries = view.putArray("runs");
        for (AgentStore.Run run : runs) {
            ObjectNode failure = AgentModelFailure.fromState(run.state());
            summaries.add(
                    AgentJson.object()
                            .put("id", run.id())
                            .put("status", run.status())
                            .put("nodeId", run.node())
                            .put("createdAt", run.createdAt().toString())
                            .putNull("updatedAt")
                            .put("ticketResolved", run.state().path("ticketResolved").asBoolean())
                            .put(
                                    "message",
                                    failure == null
                                            ? run.state().path("message").asText()
                                            : failure.path("reason").asText()));
        }
        view.put("latestRunId", runs.isEmpty() ? null : runs.get(0).id());
        source(sources, "runs", "AVAILABLE", "最多显示最近6次可访问运行，总数来自同一权限范围的完整统计。");
        ArrayNode approvalIds = view.putArray("pendingApprovalIds");
        JsonNode approvals =
                AgentJson.tree(store.pendingApprovals(50, initial.userId(), all)).path("items");
        for (JsonNode approval : approvals) {
            if (approval.path("ticketId").asLong() == ticketId
                    && (incident.isBlank()
                            || incident.equals(approval.path("incidentId").asText()))) {
                approvalIds.add(approval.path("id").asText());
            }
        }
        addDiagnosis(view, runs, gaps);
        ObjectNode verification = verification("NOT_BOUND", "尚未绑定受控现场事件", "NONE", "NONE");
        JsonNode evidence = null;
        JsonNode episode = null;
        if (!bound) {
            source(sources, "platform", "NOT_APPLICABLE", "此工单未关联受控目标和精确事件。");
            gap(gaps, "unbound", "缺少受控目标与事件关联，暂不能执行自动诊断和恢复验证。", "TICKET");
        } else if (!operational) {
            verification = verification("RESTRICTED", "现场证据仅对事件所属账户及管理员开放", "NONE", "NONE");
            source(sources, "platform", "RESTRICTED", "公开工单不包含他人的私有现场、变更和审批。");
        } else {
            Context actor = AgentTargets.bind(initial, target);
            evidence = readEvidence(actor, incident, sources);
            if (evidence != null && exact(evidence, incident, target)) {
                if (!ticket.path("episodeId").asText().isBlank()) {
                    try {
                        episode =
                                clients.call(
                                        "ticket",
                                        "/internal/agent/alerts/"
                                                + ticket.path("episodeId").asText(),
                                        "GET",
                                        null,
                                        actor);
                        source(sources, "alert", "AVAILABLE", "读取本工单关联的告警周期。");
                    } catch (BusinessException failure) {
                        source(sources, "alert", "UNAVAILABLE", "告警周期暂不可用，不能确认告警已恢复。");
                        gap(gaps, "alert-unavailable", "尚未获得告警恢复证据。", "ALERT");
                    }
                } else {
                    source(sources, "alert", "NOT_APPLICABLE", "工单没有告警周期关联。");
                }
                verification = determine(incident, evidence, episode);
                addFacts(facts, evidence, verification);
                addChanges(changes, evidence);
                if (!evidence.path("changesComplete").asBoolean()) {
                    gap(gaps, "changes-window", "变更列表是受限时间窗中的可用记录，不能据此断言没有其他变更。", "PLATFORM");
                }
            } else {
                verification =
                        verification(
                                evidence == null ? "UNAVAILABLE" : "INCIDENT_CHANGED",
                                evidence == null ? "现场证据暂不可用" : "返回证据与当前工单事件不匹配",
                                "NONE",
                                "PLATFORM");
                gap(gaps, "evidence-unavailable", "未取得可归属于本事件的现场证据，不能推断恢复状态。", "PLATFORM");
            }
        }
        view.set("verification", verification);
        view.set("stage", stage(bound, verification, runs, !approvalIds.isEmpty()));
        boolean operator =
                initial.roles().stream().anyMatch(Set.of("ADMIN", "OPS", "DEMO")::contains);
        boolean active =
                runs.stream()
                        .anyMatch(
                                r ->
                                        Set.of(
                                                        "QUEUED",
                                                        "RUNNING",
                                                        "PAUSED",
                                                        "WAITING_APPROVAL",
                                                        "WAITING_INPUT")
                                                .contains(r.status()));
        boolean liveFault =
                evidence != null
                        && evidence.path("current").asBoolean()
                        && exact(evidence, incident, target)
                        && incident.equals(evidence.path("snapshot").path("incidentId").asText())
                        && "FAULT_ACTIVE".equals(evidence.path("snapshot").path("status").asText());
        boolean canDiagnose = operational && operator && liveFault && !active;
        String reason =
                canDiagnose
                        ? "可对当前事件发起有界AI诊断，修复动作逐项审批。"
                        : !bound
                                ? "请先将工单关联到受控现场事件。"
                                : !operational
                                        ? "只有本事件所属账户或管理员可发起诊断。"
                                        : !operator
                                                ? "当前角色不能执行受控运维。"
                                                : active
                                                        ? "已有运行正在处理本事件，请继续查看当前运行。"
                                                        : "当前事件已结束或缺少仍在故障的现场证据。";
        view.set(
                "actions",
                AgentJson.object()
                        .put("canDiagnose", canDiagnose)
                        .put("reason", reason)
                        .put("definitionId", canDiagnose ? "isolated-recovery" : null));
        return view;
    }

    private JsonNode readEvidence(Context actor, String incident, ArrayNode sources) {
        if (!incident.matches("[a-zA-Z0-9_-]{1,64}")) return null;
        try {
            JsonNode result =
                    clients.call(
                            "platform",
                            AgentTargets.path(actor.targetCode())
                                    + "/incidents/"
                                    + incident
                                    + "/evidence",
                            "GET",
                            null,
                            actor);
            source(sources, "platform", "AVAILABLE", "读取精确事件的现场观测和实际变更记录。");
            return result;
        } catch (BusinessException failure) {
            source(sources, "platform", "UNAVAILABLE", "事件证据来源暂不可用，保留未知状态。");
            return null;
        }
    }

    private static boolean exact(JsonNode evidence, String incident, String target) {
        return incident.equals(evidence.path("incidentId").asText())
                && target.equals(evidence.path("targetCode").asText());
    }

    static ObjectNode determine(String incident, JsonNode evidence, JsonNode episode) {
        JsonNode record =
                evidence.path("incident").isObject() ? evidence.path("incident") : evidence;
        JsonNode snapshot = evidence.path("snapshot");
        boolean current = evidence.path("current").asBoolean();
        boolean matched =
                incident.equals(evidence.path("incidentId").asText())
                        && (!current || incident.equals(snapshot.path("incidentId").asText()));
        ObjectNode result =
                verification("INCIDENT_CHANGED", "当前现场已切换，不能使用其他事件的恢复证据", "NONE", "PLATFORM");
        result.put("incidentMatched", matched);
        if (!matched) return result;
        boolean alertResolved =
                episode != null
                        && "resolved".equalsIgnoreCase(episode.path("currentStatus").asText());
        result.put("alertResolved", alertResolved);
        if ("LIVE_TARGET_UNAVAILABLE".equals(evidence.path("captureStatus").asText())) {
            return result.put("status", "UNAVAILABLE")
                    .put("label", "现场暂不可用，历史记录不能替代当前健康验证")
                    .put("scope", "HISTORICAL")
                    .put("observedAt", blank(record.path("lastObservedAt").asText()));
        }
        if (!current) {
            String recoverySource = record.path("recoverySource").asText("NONE");
            boolean recovered =
                    !record.path("recoveredAt").asText("").isBlank()
                            && Set.of("RECOVERED", "EXPIRED_RECOVERED")
                                    .contains(record.path("status").asText());
            result.put("scope", "HISTORICAL")
                    .put("source", recoverySource)
                    .put("observedAt", blank(record.path("lastObservedAt").asText()))
                    .put("status", recovered ? "HISTORICAL_RECOVERY" : "INCIDENT_CHANGED")
                    .put("label", recovered ? "本事件已有历史恢复记录；不代表当前业务健康" : "此事件已离开当前现场，尚无历史恢复记录")
                    .put("businessHealthy", false)
                    .put("agentAttributed", false);
            return result;
        }
        JsonNode business = snapshot.path("business");
        String observed = business.path("observedAt").asText("");
        String recoverySource = snapshot.path("recoverySource").asText("NONE");
        boolean fresh = fresh(observed);
        boolean healthy =
                fresh
                        && business.path("httpStatus").asInt() == 200
                        && AgentTools.queueReady(snapshot, evidence.path("targetCode").asText());
        int consecutive = business.path("consecutiveSuccesses").asInt();
        String status =
                !fresh
                        ? "STALE"
                        : !healthy
                                ? "FAULT_ACTIVE"
                                : consecutive < 3
                                        ? "BUSINESS_PENDING"
                                        : !alertResolved ? "EPISODE_PENDING" : "RECOVERED";
        String label =
                switch (status) {
                    case "STALE" -> "业务探针时间缺失或过期，等待新鲜观测";
                    case "FAULT_ACTIVE" -> "业务探针仍失败，尚未恢复";
                    case "BUSINESS_PENDING" -> "业务探针成功，等待连续三次验证";
                    case "EPISODE_PENDING" -> "业务连续成功，等待关联告警恢复";
                    default -> "同一事件业务连续成功且告警已恢复";
                };
        return result.put("status", status)
                .put("label", label)
                .put("scope", "CURRENT")
                .put("source", recoverySource)
                .put("observedAt", blank(observed))
                .put("businessHealthy", healthy)
                .put("consecutiveSuccesses", consecutive)
                .put(
                        "agentAttributed",
                        "RECOVERED".equals(status) && "AGENT_TOOL".equals(recoverySource));
    }

    private static void addFacts(ArrayNode facts, JsonNode evidence, JsonNode verification) {
        boolean current = "CURRENT".equals(verification.path("scope").asText());
        JsonNode snapshot = current ? evidence.path("snapshot") : evidence.path("incident");
        String scope = current ? "CURRENT" : "HISTORICAL";
        String observed = verification.path("observedAt").asText(null);
        if (!current) {
            JsonNode record = evidence.path("incident");
            fact(
                    facts,
                    "historical-http",
                    "最后记录的业务HTTP探针",
                    record.path("lastHttpStatus").asText(),
                    "BUSINESS_PROBE",
                    observed,
                    scope);
            fact(
                    facts,
                    "historical-recovery",
                    "事件历史恢复来源",
                    verification.path("source").asText(),
                    "PLATFORM",
                    observed,
                    scope);
            return;
        }
        fact(
                facts,
                "business-http",
                "业务HTTP探针",
                snapshot.path("business").path("httpStatus").asText(),
                "BUSINESS_PROBE",
                observed,
                scope);
        fact(
                facts,
                "business-reason",
                "探针原因码",
                snapshot.path("business").path("reasonCode").asText(),
                "BUSINESS_PROBE",
                observed,
                scope);
        fact(
                facts,
                "config-revision",
                "已应用配置版本",
                snapshot.path("appliedRevision").asText(),
                "NACOS",
                observed,
                scope);
        fact(
                facts,
                "redis-port",
                "当前Redis端口",
                snapshot.path("redisPort").asText(),
                "NACOS",
                observed,
                scope);
        for (String field : List.of("qps", "blockedTotal", "passedTotal")) {
            fact(
                    facts,
                    "sentinel-" + field,
                    "Sentinel " + field,
                    snapshot.path("sentinel").path(field).asText(),
                    "SENTINEL",
                    observed,
                    scope);
        }
        JsonNode queue =
                snapshot.path("business").path("queue").isObject()
                        ? snapshot.path("business").path("queue")
                        : snapshot.path("queue");
        for (String field :
                List.of("messagesReady", "consumerCount", "publishedTotal", "deliveredTotal")) {
            fact(
                    facts,
                    "rabbitmq-" + field,
                    "RabbitMQ " + field,
                    queue.path(field).asText(),
                    "RABBITMQ",
                    observed,
                    scope);
        }
    }

    private static void addChanges(ArrayNode changes, JsonNode evidence) {
        int index = 0;
        var recent =
                java.util.stream.StreamSupport.stream(evidence.path("changes").spliterator(), false)
                        .sorted(
                                java.util.Comparator.comparing(
                                                (JsonNode item) ->
                                                        item.path("occurredAt")
                                                                .asText(
                                                                        item.path("observedAt")
                                                                                .asText()))
                                        .reversed())
                        .limit(12)
                        .toList();
        for (JsonNode change : recent) {
            if (index++ >= 12) break;
            ObjectNode item =
                    AgentJson.object()
                            .put("id", change.path("id").asText("change-" + index))
                            .put("kind", change.path("kind").asText("CONFIGURATION"))
                            .put("summary", text(change.path("summary").asText("已记录实际配置变更"), 300))
                            .put(
                                    "observedAt",
                                    blank(
                                            change.path("observedAt")
                                                    .asText(change.path("occurredAt").asText())))
                            .put("status", change.path("status").asText("RECORDED"))
                            .put("source", change.path("source").asText("PLATFORM"))
                            .put(
                                    "revisionBefore",
                                    blank(
                                            change.path("revisionBefore")
                                                    .asText(
                                                            change.path("before")
                                                                    .path("revision")
                                                                    .asText())))
                            .put(
                                    "revisionAfter",
                                    blank(
                                            change.path("revisionAfter")
                                                    .asText(
                                                            change.path("after")
                                                                    .path("revision")
                                                                    .asText())));
            item.set("before", configurationValues(change.path("before")));
            item.set("after", configurationValues(change.path("after")));
            changes.add(item);
        }
    }

    private static ObjectNode configurationValues(JsonNode source) {
        ObjectNode result = AgentJson.object();
        for (String key :
                List.of(
                        "redisPort",
                        "sentinelQps",
                        "sentinelResource",
                        "consumerEnabled",
                        "revision",
                        "catalogTitle",
                        "notice",
                        "discountPercent",
                        "queue",
                        "vhost")) {
            JsonNode value = source.path(key);
            if (value.isTextual()) result.put(key, text(value.asText(), 300));
            else if (value.isNumber() || value.isBoolean()) result.set(key, value);
        }
        return result;
    }

    private static void addDiagnosis(ObjectNode view, List<AgentStore.Run> runs, ArrayNode gaps) {
        AgentStore.Run diagnosed =
                runs.stream()
                        .filter(r -> r.state().path("diagnosis").isObject())
                        .findFirst()
                        .orElse(null);
        JsonNode raw = diagnosed == null ? AgentJson.object() : diagnosed.state().path("diagnosis");
        ObjectNode diagnosis =
                AgentJson.object()
                        .put("summary", text(raw.path("summary").asText(), 1400))
                        .put("recordedAt", blank(raw.path("recordedAt").asText()))
                        .put("runId", diagnosed == null ? null : diagnosed.id());
        for (String field : List.of("knownFacts", "candidateCauses", "evidenceGaps")) {
            ArrayNode items = diagnosis.putArray(field);
            raw.path(field)
                    .asText()
                    .lines()
                    .map(String::strip)
                    .filter(s -> !s.isBlank())
                    .limit(8)
                    .forEach(s -> items.add(text(s, 500)));
        }
        view.set("diagnosis", diagnosis);
        ArrayNode hypotheses = view.putArray("hypotheses");
        int index = 0;
        for (JsonNode cause : diagnosis.path("candidateCauses")) {
            ObjectNode item =
                    AgentJson.object()
                            .put("id", "model-cause-" + index++)
                            .put("title", cause.asText())
                            .put("reason", "模型候选解释，需与现场证据交叉核验。")
                            .put("source", "MODEL")
                            .put("runId", diagnosed.id());
            item.putArray("evidenceIds");
            hypotheses.add(item);
        }
        index = 0;
        for (JsonNode missing : diagnosis.path("evidenceGaps"))
            gap(gaps, "model-gap-" + index++, missing.asText(), "MODEL");
        if (diagnosed == null)
            gap(gaps, "diagnosis-pending", "可访问运行中尚无结构化模型诊断；不使用演练标签预填根因。", "MODEL");
    }

    private static ObjectNode stage(
            boolean bound, JsonNode verification, List<AgentStore.Run> runs, boolean approval) {
        String code;
        if (!bound) code = "UNBOUND";
        else if (Set.of("RECOVERED", "HISTORICAL_RECOVERY")
                .contains(verification.path("status").asText())) code = "RECOVERED";
        else if (approval) code = "WAITING_APPROVAL";
        else if (runs.isEmpty()) code = "OBSERVING";
        else {
            AgentStore.Run latest = runs.get(0);
            code =
                    switch (latest.status()) {
                        case "QUEUED", "RUNNING" ->
                                latest.state().has("recoveryVerification")
                                        ? "VERIFYING"
                                        : latest.state().has("toolIntent")
                                                ? "EXECUTING"
                                                : "DIAGNOSING";
                        case "WAITING_APPROVAL", "WAITING_INPUT" -> "WAITING_APPROVAL";
                        case "COMPLETED" -> "VERIFYING";
                        default -> "NEEDS_ATTENTION";
                    };
        }
        String label =
                switch (code) {
                    case "UNBOUND" -> "待关联现场";
                    case "OBSERVING" -> "待诊断";
                    case "DIAGNOSING" -> "AI诊断中";
                    case "WAITING_APPROVAL" -> "等待人工审批";
                    case "EXECUTING" -> "执行受控步骤";
                    case "VERIFYING" -> "等待恢复验证";
                    case "RECOVERED" -> "已有恢复证据";
                    default -> "需要人工继续处理";
                };
        return AgentJson.object()
                .put("code", code)
                .put("label", label)
                .put("basis", "以授权运行、精确事件探针和告警证据综合判断；工作流结束不等于恢复。");
    }

    private static ObjectNode verification(
            String status, String label, String scope, String source) {
        return AgentJson.object()
                .put("status", status)
                .put("label", label)
                .put("scope", scope)
                .put("source", source)
                .putNull("observedAt")
                .put("incidentMatched", false)
                .put("businessHealthy", false)
                .put("consecutiveSuccesses", 0)
                .put("alertResolved", false)
                .put("agentAttributed", false);
    }

    private static boolean fresh(String value) {
        try {
            Instant now = Instant.now();
            Instant observed = Instant.parse(value);
            return observed.isAfter(now.minusSeconds(20)) && !observed.isAfter(now.plusSeconds(2));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void fact(
            ArrayNode facts,
            String id,
            String label,
            String value,
            String source,
            String observed,
            String scope) {
        if (value == null || value.isBlank()) return;
        facts.add(
                AgentJson.object()
                        .put("id", id)
                        .put("label", label)
                        .put("value", text(value, 300))
                        .put("source", source)
                        .put("observedAt", observed)
                        .put("scope", scope));
    }

    private static void gap(ArrayNode gaps, String id, String message, String source) {
        gaps.add(AgentJson.object().put("id", id).put("message", message).put("source", source));
    }

    private static void source(ArrayNode sources, String name, String status, String message) {
        sources.add(
                AgentJson.object().put("name", name).put("status", status).put("message", message));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
