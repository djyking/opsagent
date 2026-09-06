package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens.Context;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 受限工具注册表；高风险修复绑定固定目标、版本与审批，不执行模型生成的命令。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class AgentTools {
    static final Set<String> NAMES =
            Set.of(
                    "ticket_get",
                    "ticket_history",
                    "demo_target_inspect",
                    "demo_config_restore",
                    "demo_flow_restore",
                    "demo_queue_restore",
                    "recent_changes",
                    "knowledge_search",
                    "official_docs_search",
                    "ticket_add_analysis",
                    "ticket_resolve");
    static final Set<String> HIGH =
            Set.of("demo_config_restore", "demo_flow_restore", "demo_queue_restore");
    private final AgentClients clients;
    private final OfficialDocsSearch officialDocs;

    AgentTools(AgentClients clients) {
        this(clients, new OfficialDocsSearch());
    }

    @Autowired
    AgentTools(AgentClients clients, OfficialDocsSearch officialDocs) {
        this.clients = clients;
        this.officialDocs = officialDocs;
    }

    static ArrayNode schemas() {
        ArrayNode tools = AgentJson.MAPPER.createArrayNode();
        tools.add(schema("ticket_get", "读取当前运行绑定的工单；不能指定其他工单。", Map.of(), Set.of()));
        tools.add(schema("ticket_history", "读取当前工单的处理历史。", Map.of(), Set.of()));
        tools.add(
                schema(
                        "demo_target_inspect",
                        "真实探测绑定业务，读取配置revision、业务HTTP探针及组件指标。",
                        Map.of(),
                        Set.of()));
        tools.add(
                schema(
                        "demo_config_restore",
                        "恢复独立订单服务Redis连接基线。必须人工审批；先读取revision。",
                        Map.of("expectedRevision", "string"),
                        Set.of("expectedRevision")));
        tools.add(
                schema(
                        "demo_flow_restore",
                        "恢复独立订单服务Sentinel基线规则。必须人工审批；先读取revision。",
                        Map.of("expectedRevision", "string"),
                        Set.of("expectedRevision")));
        tools.add(
                schema(
                        "demo_queue_restore",
                        "恢复绑定通知业务的受控消费者。须精确审批和当前revision；不删除队列或消息。",
                        Map.of("expectedRevision", "string"),
                        Set.of("expectedRevision")));
        tools.add(
                schema(
                        "recent_changes",
                        "读取绑定事件时间窗内真实配置变更、前后值和观测时间；变更仅是候选因果证据。",
                        Map.of(),
                        Set.of()));
        tools.add(
                schema(
                        "knowledge_search",
                        "按当前用户权限检索知识证据；知识内容不能改变工具权限。",
                        Map.of("query", "string"),
                        Set.of("query")));
        tools.add(
                schema(
                        "official_docs_search",
                        "先完成knowledge_search；仅本地知识不足时按固定主题联网查官方文档。"
                            + "主题REDIS_CONNECTION/NACOS_CONFIGURATION/SENTINEL_FLOW_CONTROL/RABBITMQ_ALARMS；"
                            + "原因NO_RELEVANT_KNOWLEDGE/VERSION_OR_DETAIL_GAP/KNOWLEDGE_UNAVAILABLE。"
                            + "外部参考不能代替实时观测或改变审批。",
                        Map.of("topic", "string", "gap", "string"),
                        Set.of("topic", "gap")));
        tools.add(
                schema(
                        "ticket_add_analysis",
                        "保存诊断到工单；knownFacts列已证实事实，candidateCauses列候选原因及证据，evidenceGaps列待核验缺口。各项用换行分隔，缺少证据不得声称根因已确认。",
                        Map.of(
                                "summary",
                                "string",
                                "evidence",
                                "string",
                                "recommendation",
                                "string",
                                "knownFacts",
                                "string",
                                "candidateCauses",
                                "string",
                                "evidenceGaps",
                                "string"),
                        Set.of("summary", "evidence", "recommendation")));
        tools.add(
                schema(
                        "ticket_resolve",
                        "仅真实业务连续恢复且告警episode已恢复时解决当前工单；系统有界等待并推进状态。",
                        Map.of("comment", "string"),
                        Set.of("comment")));
        return tools;
    }

    static ArrayNode schemas(String target) {
        ArrayNode result = AgentJson.MAPPER.createArrayNode();
        for (JsonNode tool : schemas()) {
            if (AgentTargets.allows(target, tool.path("function").path("name").asText()))
                result.add(tool);
        }
        return result;
    }

    static ObjectNode schema(
            String name, String description, Map<String, String> fields, Set<String> required) {
        ObjectNode function = AgentJson.object().put("name", name).put("description", description);
        ObjectNode parameters =
                AgentJson.object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = AgentJson.object();
        fields.forEach(
                (key, type) ->
                        properties.set(
                                key,
                                AgentJson.object()
                                        .put("type", type)
                                        .put("maxLength", fieldLimit(key))));
        if (name.equals("official_docs_search")) {
            ((ObjectNode) properties.path("topic"))
                    .set(
                            "enum",
                            AgentJson.tree(
                                    OfficialDocsSearch.CATALOG.keySet().stream()
                                            .sorted()
                                            .toList()));
            ((ObjectNode) properties.path("gap"))
                    .set(
                            "enum",
                            AgentJson.tree(OfficialDocsSearch.GAPS.stream().sorted().toList()));
        }
        parameters.set("properties", properties);
        parameters.set("required", AgentJson.tree(required.stream().sorted().toList()));
        function.set("parameters", parameters);
        ObjectNode result = AgentJson.object().put("type", "function");
        result.set("function", function);
        return result;
    }

    static void validate(String name, JsonNode args) {
        if (!NAMES.contains(name) || !args.isObject() || args.toString().length() > 12000) {
            throw AgentJson.invalid("工具名称或参数不合法");
        }
        JsonNode schema = null;
        for (JsonNode tool : schemas()) {
            if (tool.path("function").path("name").asText().equals(name))
                schema = tool.path("function");
        }
        if (schema == null) throw AgentJson.invalid("未注册工具");
        Set<String> fields = new HashSet<>();
        schema.path("parameters").path("properties").fieldNames().forEachRemaining(fields::add);
        args.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!fields.contains(field)
                                    || !args.path(field).isTextual()
                                    || args.path(field).asText().length() > fieldLimit(field)) {
                                throw AgentJson.invalid("工具参数超出固定协议");
                            }
                        });
        for (JsonNode required : schema.path("parameters").path("required")) {
            if (args.path(required.asText()).asText().isBlank())
                throw AgentJson.invalid("缺少必填工具参数");
        }
        if (HIGH.contains(name)
                && !args.path("expectedRevision").asText().matches("[a-f0-9]{64}")) {
            throw AgentJson.invalid("修复必须提供本次观测的配置版本");
        }
        if (name.equals("official_docs_search")
                && (!OfficialDocsSearch.CATALOG.containsKey(args.path("topic").asText())
                        || !OfficialDocsSearch.GAPS.contains(args.path("gap").asText()))) {
            throw AgentJson.invalid("公开文档检索只接受固定技术主题和知识不足原因");
        }
    }

    JsonNode execute(AgentStore.Run run, JsonNode call, Context actor) {
        String name = call.path("name").asText();
        validateForSnapshot(run, name, call.path("arguments"));
        if (!AgentTargets.allows(actor.targetCode(), name)) throw AgentClients.denied();
        JsonNode args = call.path("arguments");
        long ticket = run.state().path("ticketId").asLong();
        String ticketPath = "/internal/agent/tickets/" + ticket;
        String key = run.id() + ":" + call.path("id").asText();
        return switch (name) {
            case "ticket_get" -> clients.call("ticket", ticketPath, "GET", null, actor);
            case "ticket_history" ->
                    clients.call("ticket", ticketPath + "/history", "GET", null, actor);
            case "demo_target_inspect" -> inspect(actor);
            case "recent_changes" -> recentChanges(run, actor);
            case "knowledge_search" -> searchKnowledge(args, actor);
            case "official_docs_search" -> {
                if (!run.node().equals(run.state().path("knowledgeLookupNode").asText())) {
                    ObjectNode required =
                            AgentJson.object()
                                    .put("status", "KNOWLEDGE_LOOKUP_REQUIRED")
                                    .put("notice", "请先在当前诊断节点完成 knowledge_search，知识不足时再补充官方文档证据。");
                    required.putArray("citations");
                    yield required;
                }
                ObjectNode result = officialDocs.search(args.path("topic").asText());
                result.put("knowledgeGap", args.path("gap").asText());
                yield result;
            }
            case "demo_config_restore", "demo_flow_restore", "demo_queue_restore" -> {
                ObjectNode body =
                        AgentJson.object()
                                .put("incidentId", run.state().path("incidentId").asText())
                                .put(
                                        "action",
                                        switch (name) {
                                            case "demo_config_restore" -> "RESTORE_CONFIGURATION";
                                            case "demo_queue_restore" -> "RESTORE_QUEUE_CONSUMER";
                                            default -> "RESTORE_FLOW_RULE";
                                        })
                                .put("expectedRevision", args.path("expectedRevision").asText())
                                .put("idempotencyKey", key);
                yield clients.call(
                        "platform",
                        AgentTargets.path(actor.targetCode()) + "/actions",
                        "POST",
                        body,
                        actor);
            }
            case "ticket_add_analysis", "ticket_resolve" ->
                    executePrepared(call, actor, ticketPath);
            default -> throw AgentJson.invalid("工具不在允许列表中");
        };
    }

    private JsonNode searchKnowledge(JsonNode args, Context actor) {
        try {
            return clients.call(
                    "rag",
                    "/internal/rag/search",
                    "POST",
                    AgentJson.object().put("query", args.path("query").asText()).put("topK", 4),
                    actor);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.MIDDLEWARE_UNAVAILABLE) throw exception;
            ObjectNode unavailable =
                    AgentJson.object()
                            .put("status", "KNOWLEDGE_UNAVAILABLE")
                            .put("evidence", "本次知识检索服务不可用，未得到知识证据。可按公开技术主题补充官方文档。")
                            .put("query", args.path("query").asText());
            unavailable.putArray("citations");
            return unavailable;
        }
    }

    private JsonNode recentChanges(AgentStore.Run run, Context actor) {
        try {
            JsonNode result =
                    clients.call(
                            "platform",
                            AgentTargets.path(actor.targetCode())
                                    + "/incidents/"
                                    + run.state().path("incidentId").asText()
                                    + "/evidence",
                            "GET",
                            null,
                            actor);
            if (!run.state().path("incidentId").asText().equals(result.path("incidentId").asText())
                    || !actor.targetCode().equals(result.path("targetCode").asText())) {
                throw AgentJson.invalid("变更证据不属于本次运行事件");
            }
            return result;
        } catch (BusinessException failure) {
            if (failure.getErrorCode() != ErrorCode.MIDDLEWARE_UNAVAILABLE) throw failure;
            ObjectNode result =
                    AgentJson.object()
                            .put("status", "CHANGE_EVIDENCE_UNAVAILABLE")
                            .put("incidentId", run.state().path("incidentId").asText())
                            .put("targetCode", actor.targetCode())
                            .put("changesComplete", false);
            result.putArray("changes");
            return result;
        }
    }

    JsonNode inspect(Context actor) {
        return clients.call(
                "platform",
                AgentTargets.path(actor.targetCode()) + "/snapshot",
                "GET",
                null,
                actor);
    }

    static boolean requiresPreparation(String name) {
        return name.equals("ticket_add_analysis") || name.equals("ticket_resolve");
    }

    static boolean allowedBySnapshot(AgentStore.Run run, String name) {
        for (JsonNode tool : run.snapshot().path("tools")) {
            if (name.equals(tool.path("function").path("name").asText()))
                return NAMES.contains(name);
        }
        return false;
    }

    static JsonNode frozenParameters(AgentStore.Run run, String name) {
        for (JsonNode tool : run.snapshot().path("tools")) {
            if (name.equals(tool.path("function").path("name").asText()))
                return tool.path("function").path("parameters");
        }
        return AgentJson.object();
    }

    static void validateForSnapshot(AgentStore.Run run, String name, JsonNode args) {
        if (!allowedBySnapshot(run, name)) throw AgentJson.invalid("工具未包含在本次运行的冻结快照中");
        validate(name, args);
        JsonNode parameters = frozenParameters(run, name);
        args.fieldNames()
                .forEachRemaining(
                        field -> {
                            JsonNode property = parameters.path("properties").path(field);
                            if (property.isMissingNode()
                                    || args.path(field).asText().length()
                                            > property.path("maxLength").asInt(Integer.MAX_VALUE))
                                throw AgentJson.invalid("工具参数超出本次运行的冻结协议");
                            if (property.path("enum").isArray()) {
                                boolean matched = false;
                                for (JsonNode allowed : property.path("enum"))
                                    if (allowed.equals(args.path(field))) matched = true;
                                if (!matched) throw AgentJson.invalid("工具参数不在本次运行冻结的枚举中");
                            }
                        });
        for (JsonNode required : parameters.path("required")) {
            if (args.path(required.asText()).asText().isBlank())
                throw AgentJson.invalid("缺少冻结协议中的必填参数");
        }
    }

    JsonNode prepare(AgentStore.Run run, JsonNode call, Context actor) {
        String name = call.path("name").asText();
        validateForSnapshot(run, name, call.path("arguments"));
        String path = "/internal/agent/tickets/" + run.state().path("ticketId").asLong();
        if (name.equals("ticket_add_analysis")) {
            JsonNode ticket = clients.call("ticket", path, "GET", null, actor);
            return AgentJson.object()
                    .set(
                            "body",
                            ticketWrite(run, call, ticket, analysisInput(call.path("arguments"))));
        }
        if (!name.equals("ticket_resolve")) throw AgentJson.invalid("此工具不需要写入准备");
        return prepareResolve(run, call, actor, path);
    }

    private JsonNode executePrepared(JsonNode call, Context actor, String path) {
        JsonNode prepared = call.path("preparedRequest");
        if (!prepared.isObject()) throw AgentJson.invalid("缺少已持久化的写入意图");
        if (prepared.has("observation")) return prepared.path("observation");
        JsonNode body = prepared.path("body");
        if (!body.isObject()) throw AgentJson.invalid("已持久化的写入请求无效");
        boolean resolve = call.path("name").asText().equals("ticket_resolve");
        JsonNode result =
                clients.call(
                        "ticket",
                        path + (resolve ? "/transitions" : "/ai-analyses"),
                        "POST",
                        body,
                        actor);
        if (!resolve) return result;
        String next = body.path("input").path("toStatus").asText();
        ObjectNode response =
                AgentJson.object()
                        .put("resolved", next.equals("RESOLVED"))
                        .put("toStatus", next)
                        .put("recoverySource", prepared.path("recoverySource").asText())
                        .put("reason", next.equals("RESOLVED") ? "业务和告警已验证恢复" : "状态已推进，可继续解决");
        response.set("ticket", result);
        response.set("evidence", prepared.path("evidence"));
        return response;
    }

    private JsonNode prepareResolve(AgentStore.Run run, JsonNode call, Context actor, String path) {
        JsonNode snapshot = inspect(actor);
        if (!snapshot.path("incidentId").asText().equals(run.state().path("incidentId").asText())) {
            return waiting("INCIDENT_CHANGED", "目标已不属于本次演练，不能使用其他演练的恢复证据", snapshot, null);
        }
        String source = snapshot.path("recoverySource").asText();
        if (source.equals("TTL_GUARD")) {
            return waiting("TTL_RECOVERED", "TTL保护机制已恢复，不能归为Agent修复", snapshot, null);
        }
        if (source.equals("MANUAL")) {
            return waiting("MANUAL_RECOVERED", "目标由人工恢复，自动收口交由人工核验", snapshot, null);
        }
        if (run.state().has("approvedRepair") && !source.equals("AGENT_TOOL")) {
            return waiting("UNATTRIBUTED_RECOVERY", "新鲜观测未能确认本次Agent修复来源，需要人工核验", snapshot, null);
        }
        JsonNode business = snapshot.path("business");
        if (business.path("httpStatus").asInt() != 200
                || !queueReady(snapshot, actor.targetCode())
                || business.path("consecutiveSuccesses").asInt() < 3
                || !fresh(business.path("observedAt").asText())) {
            return waiting("BUSINESS_PENDING", "等待连续三次新鲜业务成功探针", snapshot, null);
        }
        JsonNode episode =
                clients.call(
                        "ticket",
                        "/internal/agent/alerts/" + run.state().path("episodeId").asText(),
                        "GET",
                        null,
                        actor);
        if (!episode.path("currentStatus").asText().equalsIgnoreCase("resolved")) {
            return waiting("EPISODE_PENDING", "监控恢复事件尚未到达，保留工单待验证", snapshot, episode);
        }
        JsonNode ticket = clients.call("ticket", path, "GET", null, actor);
        String status = ticket.path("status").asText();
        if (status.equals("RESOLVED") || status.equals("CLOSED")) {
            ObjectNode observation =
                    AgentJson.object()
                            .put("resolved", true)
                            .put("toStatus", status)
                            .put("recoverySource", snapshot.path("recoverySource").asText());
            observation.set("ticket", ticket);
            observation.set("evidence", recoveryEvidence(snapshot, episode));
            return AgentJson.object().set("observation", observation);
        }
        String next =
                switch (status) {
                    case "CREATED" -> "ASSIGNED";
                    case "ASSIGNED", "SUSPENDED" -> "PROCESSING";
                    case "PROCESSING", "WAITING_CONFIRM" -> "RESOLVED";
                    default -> "";
                };
        if (next.isEmpty()) return waiting("MANUAL_REQUIRED", "当前状态需要人工处理", snapshot, episode);
        String comment =
                "系统恢复验证：incident="
                        + snapshot.path("incidentId").asText()
                        + "，HTTP="
                        + business.path("httpStatus").asInt()
                        + "，连续成功="
                        + business.path("consecutiveSuccesses").asInt()
                        + "，观测时间="
                        + business.path("observedAt").asText()
                        + "，告警="
                        + episode.path("currentStatus").asText()
                        + "，恢复来源="
                        + source
                        + "。"
                        + call.path("arguments").path("comment").asText();
        ObjectNode input =
                AgentJson.object()
                        .put("toStatus", next)
                        .put("comment", comment.substring(0, Math.min(comment.length(), 500)));
        ObjectNode prepared =
                AgentJson.object().put("recoverySource", snapshot.path("recoverySource").asText());
        prepared.set("body", ticketWrite(run, call, ticket, input));
        prepared.set("evidence", recoveryEvidence(snapshot, episode));
        return prepared;
    }

    private static JsonNode waiting(
            String code, String reason, JsonNode snapshot, JsonNode episode) {
        ObjectNode observation =
                AgentJson.object()
                        .put("resolved", false)
                        .put("reasonCode", code)
                        .put(
                                "retryable",
                                code.equals("BUSINESS_PENDING") || code.equals("EPISODE_PENDING"))
                        .put("reason", reason);
        observation.set("evidence", recoveryEvidence(snapshot, episode));
        return AgentJson.object().set("observation", observation);
    }

    private static ObjectNode recoveryEvidence(JsonNode snapshot, JsonNode episode) {
        ObjectNode evidence =
                AgentJson.object()
                        .put("incidentId", snapshot.path("incidentId").asText())
                        .put("targetCode", snapshot.path("targetCode").asText(AgentTargets.ORDER))
                        .put("recoverySource", snapshot.path("recoverySource").asText())
                        .put("expectedRevision", snapshot.path("expectedRevision").asText());
        JsonNode business = snapshot.path("business");
        evidence.set(
                "business",
                AgentJson.object()
                        .put("httpStatus", business.path("httpStatus").asInt())
                        .put("consecutiveSuccesses", business.path("consecutiveSuccesses").asInt())
                        .put("observedAt", business.path("observedAt").asText()));
        ObjectNode businessEvidence = (ObjectNode) evidence.path("business");
        for (String field : Set.of("reasonCode", "messageId", "deliveredAt", "receiptStore")) {
            if (business.has(field)) businessEvidence.set(field, business.path(field).deepCopy());
        }
        JsonNode queue =
                business.path("queue").isObject() ? business.path("queue") : snapshot.path("queue");
        if (queue.isObject()) evidence.set("queue", queue.deepCopy());
        if (business.has("queueDrained"))
            businessEvidence.put("queueDrained", business.path("queueDrained").asBoolean());
        if (episode != null)
            evidence.put("episodeStatus", episode.path("currentStatus").asText())
                    .put("episodeId", episode.path("episodeId").asText())
                    .put("episodeResolvedAt", episode.path("resolvedAt").asText());
        return evidence;
    }

    static boolean queueReady(JsonNode snapshot, String target) {
        if (!AgentTargets.NOTIFICATION.equals(target)) return true;
        JsonNode business = snapshot.path("business");
        try {
            Instant.parse(business.path("deliveredAt").asText());
        } catch (RuntimeException invalidTime) {
            return false;
        }
        JsonNode queue =
                business.path("queue").isObject() ? business.path("queue") : snapshot.path("queue");
        return business.path("queueDrained").asBoolean()
                && business.path("messageId")
                        .asText()
                        .matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                && "ISOLATED_REDIS_READ_BACK".equals(business.path("receiptStore").asText())
                && "NOTIFICATION_DELIVERED".equals(business.path("reasonCode").asText())
                && "opsagent.demo.notification.v1".equals(queue.path("queue").asText())
                && "notifications".equals(queue.path("vhost").asText())
                && queue.path("messagesReady").isIntegralNumber()
                && queue.path("messagesReady").asLong(-1) == 0
                && queue.path("consumerCount").isIntegralNumber()
                && queue.path("consumerCount").asInt() >= 1;
    }

    private static JsonNode analysisInput(JsonNode args) {
        ObjectNode input = AgentJson.object();
        String summary = args.path("summary").asText();
        if (args.has("knownFacts") || args.has("candidateCauses") || args.has("evidenceGaps")) {
            summary =
                    bounded(summary, 350)
                            + "\n已知事实："
                            + bounded(args.path("knownFacts").asText(), 300)
                            + "\n候选原因："
                            + bounded(args.path("candidateCauses").asText(), 400)
                            + "\n证据缺口："
                            + bounded(args.path("evidenceGaps").asText(), 250);
        }
        return input.put("summary", summary)
                .put("evidence", args.path("evidence").asText())
                .put("recommendation", args.path("recommendation").asText());
    }

    private static String bounded(String value, int max) {
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }

    private static boolean fresh(String observed) {
        try {
            Instant time = Instant.parse(observed);
            Instant now = Instant.now();
            return time.isAfter(now.minusSeconds(20)) && !time.isAfter(now.plusSeconds(2));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static int fieldLimit(String field) {
        return switch (field) {
            case "summary" -> 1400;
            case "evidence" -> 1000;
            case "knownFacts", "candidateCauses", "evidenceGaps" -> 800;
            case "recommendation", "comment" -> 500;
            case "query" -> 2000;
            case "expectedRevision" -> 64;
            default -> 4000;
        };
    }

    private ObjectNode ticketWrite(
            AgentStore.Run run, JsonNode call, JsonNode ticket, JsonNode input) {
        String id = call.path("id").asText();
        if (call.path("name").asText().equals("ticket_resolve")) {
            int step = call.path("transitionStep").asInt();
            if (step < 0 || step >= 3) throw AgentJson.invalid("工单状态推进次数已达上限");
            id += ":transition:" + step;
        }
        ObjectNode body =
                AgentJson.object()
                        .put("runId", run.id())
                        .put("toolCallId", id)
                        .put("idempotencyKey", run.id() + ":" + id)
                        .put("expectedVersion", ticket.path("version").asInt());
        body.set("input", input);
        return body;
    }
}
