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
            throw AgentClients.denied();
        }
        actor = AgentTargets.bind(actor, ticket.path("affectedCiCode").asText());
        if (actor.roles().contains("DEMO") && !definition.equals("isolated-recovery"))
            throw AgentClients.denied();
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
        return store.pendingApprovals(limit, actor.userId(), actor.roles().contains("ADMIN"));
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
        if (store.get(id).owner() != actor.userId() && !actor.roles().contains("ADMIN"))
            throw AgentClients.denied();
    }

    void decide(String id, int revision, String hash, boolean approved, String reason) {
        JsonNode approval = store.approval(id);
        own(approval.path("run_id").asText());
        if (reason == null || reason.length() > 500) throw AgentJson.invalid("审批说明超出范围");
        AgentStore.Run run = store.get(approval.path("run_id").asText());
        if (!run.status().equals("WAITING_APPROVAL") && !run.status().equals("WAITING_INPUT")) {
            throw AgentJson.invalid("运行当前没有等待审批或补充信息");
        }
        store.decide(id, revision, hash, approved, reason, SecurityUsers.current().userId());
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
