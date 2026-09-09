package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens.Context;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 面向用户及告警事件的执行入口，统一实施模型能力与工单所属范围约束。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class AgentService {
    private final AgentStore store;
    private final AgentClients clients;

    @org.springframework.beans.factory.annotation.Value("${ops.agent.run-token-budget:100000}")
    private int runTokenBudget = 100000;

    private int tokenLimit() {
        if (runTokenBudget == 0) return 0;
        return Math.max(1000, Math.min(runTokenBudget, 100000));
    }

    private String tokenBudgetMode() {
        return tokenLimit() == 0 ? "UNLIMITED" : "LIMITED";
    }

    AgentService(AgentStore store, AgentClients clients) {
        this.store = store;
        this.clients = clients;
    }

    JsonNode models() {
        return clients.call(
                "rag",
                "/internal/ai/models",
                "GET",
                null,
                clients.current(SecurityUsers.current(), "model-catalog"));
    }

    JsonNode probe(String provider) {
        admin();
        if (!SetHolder.PROVIDERS.contains(provider)) throw AgentJson.invalid("模型提供商无效");
        return clients.call(
                "rag",
                "/internal/ai/models/" + provider.toLowerCase(java.util.Locale.ROOT) + "/probe",
                "POST",
                null,
                clients.current(SecurityUsers.current(), "model-probe"));
    }

    String manual(long ticket, String definition, String provider, String requestId) {
        if (!requestId.matches("[a-zA-Z0-9_-]{10,80}")) throw AgentJson.invalid("请求幂等键无效");
        Context actor = clients.current(SecurityUsers.current(), UUID.randomUUID().toString());
        return create(
                ticket, definition, provider, "manual:" + actor.userId() + ":" + requestId, actor);
    }

    String automatic(JsonNode event) {
        long owner = event.path("ownerActorId").asLong();
        if (owner == 0
                || !event.path("environment").asText().equals("ISOLATED")
                || event.path("incidentId").asText().isBlank())
            throw AgentJson.invalid("非隔离演练事件不自动执行");
        String bound =
                store.incidentRun("isolated-recovery", owner, event.path("incidentId").asText());
        if (bound != null) return bound;
        Context actor =
                clients.refresh(
                        new Context(
                                owner,
                                "event-owner",
                                owner < 0 ? List.of("DEMO") : List.of("ADMIN", "OPS", "USER"),
                                UUID.randomUUID().toString(),
                                event.path("targetCode")
                                        .asText(
                                                event.path("affectedCiCode")
                                                        .asText(AgentTargets.ORDER)),
                                Instant.now().plusSeconds(900)));
        JsonNode target =
                clients.call(
                        "platform",
                        AgentTargets.path(actor.targetCode()) + "/snapshot",
                        "GET",
                        null,
                        actor);
        if (!target.path("incidentId").asText().equals(event.path("incidentId").asText())
                || !target.path("status").asText().equals("FAULT_ACTIVE")
                || target.path("recoverySource").asText().equals("TTL_GUARD")) {
            throw AgentJson.invalid("自动执行仅适用于当前仍故障的隔离演练");
        }
        return create(
                event.path("ticketId").asLong(),
                "isolated-recovery",
                "DEEPSEEK",
                "alert:" + event.path("episodeId").asText(),
                actor);
    }

    private String create(
            long ticketId, String definition, String provider, String trigger, Context actor) {
        return create(ticketId, definition, provider, trigger, actor, null, null);
    }

    private String create(
            long ticketId,
            String definition,
            String provider,
            String trigger,
            Context actor,
            ObjectNode takeover,
            AgentStore.Run source) {
        JsonNode ticket =
                clients.call(
                        "ticket",
                        "/internal/agent/tickets/" + ticketId + "/workspace-context",
                        "GET",
                        null,
                        actor);
        if (!ticket.path("environment").asText().equals("ISOLATED")
                || !AgentTargets.supported(ticket.path("affectedCiCode").asText())
                || actor.roles().stream()
                        .noneMatch(java.util.Set.of("ADMIN", "OPS", "DEMO")::contains)
                || ticket.path("incidentId").asText().isBlank()
                || (ticket.path("ownerActorId").asLong() != actor.userId()
                        && !actor.roles().contains("ADMIN"))) {
            throw new AgentAccessFailure(
                    ticket.path("ownerActorId").asLong() != actor.userId()
                                    && !actor.roles().contains("ADMIN")
                            ? "RUN_NOT_OWNED"
                            : "ISOLATED_SCOPE_REQUIRED");
        }
        actor = AgentTargets.bind(actor, ticket.path("affectedCiCode").asText());
        if (takeover != null
                && (!source.state()
                                .path("incidentId")
                                .asText()
                                .equals(ticket.path("incidentId").asText())
                        || !takeover.path("targetCode").asText().equals(actor.targetCode())))
            throw new AgentAccessFailure("INCIDENT_CHANGED");
        if (actor.roles().contains("DEMO") && !definition.equals("isolated-recovery"))
            throw new AgentAccessFailure("WORKFLOW_NOT_ALLOWED");
        JsonNode model = null;
        JsonNode models = clients.call("rag", "/internal/ai/models", "GET", null, actor);
        for (JsonNode candidate : models.path("models")) {
            if (candidate.path("provider").asText().equalsIgnoreCase(provider)
                    && candidate.path("toolCalling").asBoolean()) model = candidate;
        }
        if (model == null) throw AgentJson.invalid("该模型尚未通过原生工具能力验证");
        ObjectNode snapshot =
                AgentJson.object()
                        .put("toolRegistryVersion", "isolated-tools-v4-evidence-refs")
                        .put("evidenceRegistryVersion", AgentEvidenceRegistry.VERSION)
                        .put("tokenBudgetMode", tokenBudgetMode())
                        .put("tokenBudget", tokenLimit())
                        .put("policyVersion", "isolated-owner-approval-v1");
        snapshot.set("graph", store.version(definition));
        snapshot.set("model", model);
        snapshot.set("tools", AgentTools.schemas(actor.targetCode()));
        if (takeover != null) snapshot.set("takeover", takeover);
        snapshot.put("hash", AgentJson.hash(snapshot));
        ObjectNode state =
                AgentJson.object()
                        .put("runId", actor.runId())
                        .put("ticketId", ticketId)
                        .put("targetCode", actor.targetCode())
                        .put("incidentId", ticket.path("incidentId").asText())
                        .put("episodeId", ticket.path("episodeId").asText())
                        .put("trigger", trigger)
                        .put("deadline", actor.validUntil().toString())
                        .put("turns", 0)
                        .put("toolCount", 0)
                        .put("tokens", 0)
                        .put("tokenBudget", tokenLimit())
                        .put("tokenBudgetMode", tokenBudgetMode())
                        .put("ticketResolved", false);
        state.set("actor", clients.actorJson(actor));
        if (takeover != null) {
            state.set("takeover", takeover);
            return store.createTakeover(
                    source, definition, trigger, actor.userId(), snapshot, state);
        }
        return store.create(definition, trigger, actor.userId(), snapshot, state);
    }

    JsonNode detail(String id) {
        own(id);
        AgentStore.Run run = store.get(id);
        ObjectNode view =
                AgentJson.object()
                        .put("id", id)
                        .put("ownerId", run.owner())
                        .put("status", run.status())
                        .put("nodeId", run.node())
                        .put("createdAt", run.createdAt().toString())
                        .put("pauseRequested", run.paused());
        view.set("snapshot", run.snapshot());
        ObjectNode state = run.state().deepCopy();
        ObjectNode modelFailure = AgentModelFailure.fromState(state);
        if (modelFailure != null) {
            state.set("modelFailure", modelFailure);
            state.put("message", modelFailure.path("reason").asText());
        }
        state.remove("actor");
        state.remove("modelIntent");
        state.remove("messages");
        view.set("state", state);
        view.set("approvals", AgentJson.tree(store.approvals(id)));
        view.set("authorization", authorization(run));
        JsonNode recovery = store.takeoverRecovery(id);
        if (recovery != null) view.set("takeoverRecovery", recovery);
        return view;
    }

    Map<String, Object> runs(int page, int size, Long ticketId, String incidentId) {
        Context actor = clients.current(SecurityUsers.current(), "run-list");
        boolean all = actor.roles().contains("ADMIN");
        return Map.of(
                "items",
                store.runs(page, size, actor.userId(), all, ticketId, incidentId),
                "total",
                store.count(actor.userId(), all, ticketId, incidentId));
    }

    JsonNode usage(String id) {
        own(id);
        AgentStore.Run run = store.get(id);
        ObjectNode result = AgentJson.object().put("runId", id);
        result.set("budget", AgentRunUsage.budget(run.state()));
        result.set("embedding", AgentRunUsage.embedding(run.state()));
        try {
            Context actor =
                    clients.current(SecurityUsers.current(), run.state().path("runId").asText(id));
            result.set(
                    "model",
                    clients.call(
                            "rag",
                            "/internal/ai/runs/" + actor.runId() + "/usage",
                            "GET",
                            null,
                            actor));
        } catch (RuntimeException unavailable) {
            result.set(
                    "model",
                    AgentJson.object()
                            .put("availability", "UNAVAILABLE")
                            .put("unknownCountIsLowerBound", true)
                            .put("coverage", "UNAVAILABLE"));
        }
        return result;
    }

    Map<String, Object> pendingApprovals(int limit) {
        if (limit < 1 || limit > 50) throw AgentJson.invalid("待审批数量范围应为 1 至 50");
        Context actor = clients.current(SecurityUsers.current(), "pending-approvals");
        Map<String, Object> pending =
                store.pendingApprovals(limit, actor.userId(), actor.roles().contains("ADMIN"));
        var items = AgentJson.tree(pending.get("items"));
        for (JsonNode item : items) {
            ObjectNode access = authorization(store.get(item.path("run_id").asText()));
            ((ObjectNode) item).set("authorization", access);
            ((ObjectNode) item)
                    .put("canOperate", access.path("canOperate").asBoolean())
                    .put("hint", access.path("message").asText());
        }
        return Map.of("items", items, "total", pending.get("total"));
    }

    Map<String, Object> summary() {
        Context actor = clients.current(SecurityUsers.current(), "run-summary");
        boolean all = actor.roles().contains("ADMIN");
        Map<String, Long> counts = store.statusCounts(actor.userId(), all);
        var activeStates =
                List.of("QUEUED", "RUNNING", "PAUSED", "WAITING_APPROVAL", "WAITING_INPUT");
        return Map.of(
                "scope",
                all ? "ADMIN" : "OWNER",
                "statusCounts",
                counts,
                "totalRuns",
                counts.values().stream().mapToLong(Long::longValue).sum(),
                "activeRuns",
                activeStates.stream().mapToLong(s -> counts.getOrDefault(s, 0L)).sum(),
                "activeDefinition",
                activeStates,
                "pendingApprovals",
                store.pendingApprovals(50, actor.userId(), all).get("total"));
    }

    void own(String id) {
        OpsPrincipal principal = SecurityUsers.current();
        Context actor = clients.current(principal, "run-management");
        AgentStore.Run run = store.get(id);
        if (run.owner() != actor.userId() && !actor.roles().contains("ADMIN"))
            throw new AgentAccessFailure("RUN_NOT_OWNED");
        if (actor.roles().contains("DEMO") && !isolated(run))
            throw new AgentAccessFailure("ISOLATED_SCOPE_REQUIRED");
    }

    void decide(String id, int revision, String hash, boolean approved, String reason) {
        JsonNode approval = store.approval(id);
        own(approval.path("run_id").asText());
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw AgentJson.invalid("审批原因必填，且不能超过500字");
        AgentStore.Run run = store.get(approval.path("run_id").asText());
        if (!run.status().equals("WAITING_APPROVAL") && !run.status().equals("WAITING_INPUT")) {
            throw AgentJson.invalid("运行当前没有等待审批或补充信息");
        }
        if (approved) {
            // An administrator decision does not replace the original executor's identity.
            Context executor = clients.refresh(clients.fromState(run.state()));
            if (run.state().path("authorizationFailure").path("requiresNewRun").asBoolean())
                throw new AgentAccessFailure("NEW_RUN_REQUIRED");
            JsonNode intent = run.state().path("toolIntent");
            if (java.util.Set.of("demo_config_restore", "demo_flow_restore", "demo_queue_restore")
                    .contains(intent.path("name").asText())) {
                JsonNode target = currentTakeoverTarget(run, executor);
                if (!target.path("expectedRevision")
                        .asText()
                        .equals(intent.path("arguments").path("expectedRevision").asText()))
                    throw new AgentAccessFailure("TARGET_REVISION_CHANGED");
            }
        }
        store.decide(id, revision, hash, approved, reason, SecurityUsers.current().userId());
    }

    private boolean isolated(AgentStore.Run run) {
        return AgentTargets.supported(run.state().path("targetCode").asText(AgentTargets.ORDER))
                && !run.state().path("incidentId").asText().isBlank()
                && !run.snapshot().has("configurationProposal");
    }

    private ObjectNode authorization(AgentStore.Run run) {
        String code = "";
        boolean invalidIdentity = false;
        try {
            Context original = clients.fromState(run.state());
            JsonNode identity = clients.inspectActor(original);
            if (identity.path("userId").asLong() != run.owner()) code = "ACTOR_ID_MISMATCH";
            else if (!identity.path("active").asBoolean()) {
                code = AgentClients.inactiveReason(identity);
                invalidIdentity = true;
            } else if (original.roles().stream()
                    .noneMatch(
                            role -> {
                                for (JsonNode current : identity.path("roles"))
                                    if (role.equals(current.asText())) return true;
                                return false;
                            })) {
                code = "ACTOR_ROLE_REVOKED";
                invalidIdentity = true;
            } else if (run.state().path("authorizationFailure").path("requiresNewRun").asBoolean())
                code = "NEW_RUN_REQUIRED";
            else if (!Instant.parse(run.state().path("deadline").asText()).isAfter(Instant.now()))
                code = "RUN_DEADLINE_EXPIRED";
        } catch (RuntimeException unavailable) {
            code = "ACTOR_STATUS_UNAVAILABLE";
        }
        boolean canTakeover =
                invalidIdentity
                        && isolated(run)
                        && AgentStore.takeoverIdle(run)
                        && SecurityUsers.current().roles().contains("ADMIN");
        String hint = code.isBlank() ? "" : AgentAccessFailure.message(code);
        return AgentJson.object()
                .put("canOperate", code.isBlank())
                .put("reasonCode", code)
                .put("message", hint)
                .put("hint", hint)
                .put("takeoverRequired", invalidIdentity)
                .put("canTakeover", canTakeover);
    }

    JsonNode takeoverPreview(String id) {
        admin();
        AgentStore.Run source = store.get(id);
        ObjectNode access = authorization(source);
        if (!access.path("canTakeover").asBoolean())
            throw new AgentAccessFailure(
                    access.path("takeoverRequired").asBoolean()
                            ? "RUN_BUSY"
                            : "TAKEOVER_NOT_REQUIRED");
        Context actor =
                AgentTargets.bind(
                        clients.current(SecurityUsers.current(), "takeover-preview"),
                        source.state().path("targetCode").asText());
        JsonNode target = currentTakeoverTarget(source, actor);
        return AgentJson.object()
                .put("sourceRunId", id)
                .put("originalOwnerId", source.owner())
                .put("incidentId", source.state().path("incidentId").asText())
                .put("targetCode", actor.targetCode())
                .put("expectedRevision", target.path("expectedRevision").asText())
                .put("canTakeover", true)
                .put("reasonCode", access.path("reasonCode").asText())
                .put("hint", "将创建管理员负责的新运行，重新诊断并生成新的精确审批；旧运行保持历史结果。");
    }

    String takeover(
            String id, String requestId, String reason, String incidentId, String revision) {
        admin();
        if (!requestId.matches("[a-zA-Z0-9_-]{10,80}")
                || reason.isBlank()
                || reason.length() > 500
                || !revision.matches("[a-f0-9]{64}")) throw AgentJson.invalid("接管原因、请求标识或现场版本无效");
        long operator = SecurityUsers.current().userId();
        String existing = store.takeoverRun(id, operator, requestId, reason, incidentId, revision);
        if (existing != null) return existing;
        AgentStore.Run source = store.get(id);
        ObjectNode authorization = authorization(source);
        if (!authorization.path("canTakeover").asBoolean())
            throw new AgentAccessFailure(
                    authorization.path("takeoverRequired").asBoolean()
                            ? "RUN_BUSY"
                            : "TAKEOVER_NOT_REQUIRED");
        if (!source.state().path("incidentId").asText().equals(incidentId))
            throw new AgentAccessFailure("INCIDENT_CHANGED");
        Context actor =
                AgentTargets.bind(
                        clients.current(SecurityUsers.current(), UUID.randomUUID().toString()),
                        source.state().path("targetCode").asText());
        JsonNode target = currentTakeoverTarget(source, actor);
        if (!revision.equals(target.path("expectedRevision").asText()))
            throw new AgentAccessFailure("TARGET_REVISION_CHANGED");
        ObjectNode handoff =
                AgentJson.object()
                        .put("sourceRunId", id)
                        .put("originalOwnerId", source.owner())
                        .put("takenOverBy", operator)
                        .put("requestId", requestId)
                        .put("reason", reason)
                        .put("incidentId", incidentId)
                        .put("targetCode", actor.targetCode())
                        .put("expectedRevision", revision)
                        .put("at", Instant.now().toString())
                        .put("identityReasonCode", authorization.path("reasonCode").asText());
        return create(
                source.state().path("ticketId").asLong(),
                "isolated-recovery",
                source.snapshot().path("model").path("provider").asText(),
                "takeover:" + id,
                actor,
                handoff,
                source);
    }

    private JsonNode currentTakeoverTarget(AgentStore.Run source, Context actor) {
        JsonNode target =
                clients.call(
                        "platform",
                        AgentTargets.path(actor.targetCode()) + "/snapshot",
                        "GET",
                        null,
                        actor);
        if (!"ISOLATED_DEMO".equals(target.path("scope").asText())
                || !actor.targetCode().equals(target.path("targetCode").asText()))
            throw new AgentAccessFailure("ISOLATED_SCOPE_REQUIRED");
        if (!source.state().path("incidentId").asText().equals(target.path("incidentId").asText()))
            throw new AgentAccessFailure("INCIDENT_CHANGED");
        if (!"FAULT_ACTIVE".equals(target.path("status").asText()))
            throw new AgentAccessFailure("TARGET_ALREADY_RECOVERED");
        if (!"APPLIED".equals(target.path("configurationStatus").asText())
                || !target.path("expectedRevision").asText().matches("[a-f0-9]{64}"))
            throw new AgentAccessFailure("TARGET_REVISION_CHANGED");
        return target;
    }

    void admin() {
        if (!clients.current(SecurityUsers.current(), "definition-management")
                .roles()
                .contains("ADMIN")) {
            throw AgentClients.denied();
        }
    }

    Map<String, Object> limits() {
        return Map.of(
                "maxModelTurns",
                12,
                "maxToolCalls",
                18,
                "maxTotalTokens",
                tokenLimit(),
                "tokenBudgetMode",
                tokenBudgetMode(),
                "maximumMinutes",
                15,
                "maxConcurrentRuns",
                8,
                "repairApproval",
                "EXACT_CALL_REQUIRED",
                "scope",
                "ISOLATED_DEMO_ONLY");
    }

    /**
     * @author heyu
     */
    private static final class SetHolder {
        private static final java.util.Set<String> PROVIDERS =
                java.util.Set.of("DEEPSEEK", "OPENAI", "KIMI");
    }
}
