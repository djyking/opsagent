package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens.Context;
import com.opsagent.common.security.OpsPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * @author heyu
 */
class AgentTestSupport {
    final JdbcTemplate jdbc;
    final AgentStore store;
    final FakeClients clients;
    final AgentRuntime runtime;

    AgentTestSupport() {
        this(new FakeClients());
    }

    AgentTestSupport(FakeClients clients) {
        this.clients = clients;
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(source);
        store = new AgentStore(jdbc, new DataSourceTransactionManager(source));
        store.initialize();
        runtime = new AgentRuntime(store, clients, new AgentTools(clients), true);
    }

    String create(String trigger, long owner) {
        String id = UUID.randomUUID().toString();
        ObjectNode snapshot = AgentJson.object();
        snapshot.set("graph", WorkflowGraph.builtin());
        snapshot.set(
                "model", AgentJson.object().put("provider", "DEEPSEEK").put("model", "test-model"));
        snapshot.set("tools", AgentTools.schemas(AgentTargets.ORDER));
        ObjectNode state =
                AgentJson.object()
                        .put("runId", id)
                        .put("ticketId", 7)
                        .put("targetCode", AgentTargets.ORDER)
                        .put("incidentId", "incident-1")
                        .put("episodeId", "a".repeat(64))
                        .put("trigger", trigger)
                        .put("deadline", Instant.now().plusSeconds(600).toString())
                        .put("turns", 0)
                        .put("tokens", 0)
                        .put("toolCount", 0);
        state.set(
                "actor",
                clients.actorJson(
                        new Context(
                                owner,
                                "owner",
                                List.of("DEMO"),
                                id,
                                "ops-demo-order-service",
                                Instant.now().plusSeconds(600))));
        return store.create("isolated-recovery", trigger, owner, snapshot, state);
    }

    void step() {
        jdbc.update("UPDATE agent_run SET next_attempt=TIMESTAMPADD(SECOND,-1,NOW(3))");
        runtime.tick();
    }

    void recoverLease() {
        jdbc.update(
                "UPDATE agent_run SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE"
                        + " status='RUNNING'");
    }

    void elapseRecoveryDelay(String id) {
        var run = store.get(id);
        ((ObjectNode) run.state().path("toolIntent"))
                .put("notBefore", Instant.now().minusSeconds(1).toString());
        jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", run.state().toString(), id);
    }

    void finish(String id) {
        for (int i = 0; i < 20 && store.get(id).status().equals("QUEUED"); i++) step();
    }

    static JsonNode finalResponse() {
        return AgentJson.read(
                """
                {"outcome":"FINAL","usageKnown":true,"totalTokens":10,
                 "assistantMessage":{"role":"assistant","content":"处置分析已完成，系统继续验证恢复证据"}}
                """);
    }

    static JsonNode response(String name, JsonNode arguments) {
        ObjectNode result =
                AgentJson.object()
                        .put("outcome", "TOOL_CALLS")
                        .put("usageKnown", true)
                        .put("totalTokens", 10);
        ObjectNode message = AgentJson.object().put("role", "assistant").put("content", "");
        ObjectNode call = AgentJson.object().put("id", "provider-call-1").put("type", "function");
        call.set(
                "function",
                AgentJson.object().put("name", name).put("arguments", arguments.toString()));
        message.putArray("tool_calls").add(call);
        result.set("assistantMessage", message);
        return result;
    }

    /**
     * @author heyu
     */
    static class FakeClients extends AgentClients {
        final List<JsonNode> modelRequests = new ArrayList<>();
        final List<JsonNode> writes = new ArrayList<>();
        final Map<String, JsonNode> committedRequests = new HashMap<>();
        final Map<String, JsonNode> committedResults = new HashMap<>();
        int ticketReads;
        int targetReads;
        int actions;
        int version = 1;
        String status = "PROCESSING";
        String incident = "incident-1";
        String observedAt;
        String recoverySource = "AGENT_TOOL";
        String episodeStatus = "resolved";
        boolean crashAfterWrite;
        Function<JsonNode, JsonNode> model = request -> response("ticket_get", AgentJson.object());

        FakeClients() {
            super(
                    "test-secret-longer-than-32-characters-only",
                    "http://unused",
                    "http://unused",
                    "http://unused",
                    "http://unused");
        }

        @Override
        Context refresh(Context actor) {
            return actor;
        }

        @Override
        Context current(OpsPrincipal principal, String run) {
            return new Context(
                    principal.userId(),
                    principal.username(),
                    principal.roles(),
                    run,
                    "ops-demo-order-service",
                    Instant.now().plusSeconds(600));
        }

        @Override
        JsonNode call(String audience, String path, String method, JsonNode body, Context actor) {
            if (audience.equals("auth")) {
                ObjectNode identity =
                        AgentJson.object()
                                .put("active", true)
                                .put("userId", actor.userId())
                                .put("expiresAt", Instant.now().plusSeconds(600).toString());
                identity.set("roles", AgentJson.tree(actor.roles()));
                return identity;
            }
            if (path.equals("/internal/ai/turns")) {
                modelRequests.add(body.deepCopy());
                return model.apply(body);
            }
            if (path.endsWith("/snapshot")) {
                targetReads++;
                ObjectNode result =
                        AgentJson.object()
                                .put("incidentId", incident)
                                .put("status", "BASELINE")
                                .put("recoverySource", recoverySource);
                result.set(
                        "business",
                        AgentJson.object()
                                .put("httpStatus", 200)
                                .put("consecutiveSuccesses", 3)
                                .put(
                                        "observedAt",
                                        observedAt == null
                                                ? Instant.now().toString()
                                                : observedAt));
                return result;
            }
            if (path.endsWith("/actions")) {
                actions++;
                return AgentJson.object().put("actionAccepted", true);
            }
            if (path.contains("/alerts/"))
                return AgentJson.object().put("currentStatus", episodeStatus);
            if (path.endsWith("/ai-analyses") || path.endsWith("/transitions")) {
                writes.add(body.deepCopy());
                String key = body.path("idempotencyKey").asText();
                if (committedRequests.containsKey(key)) {
                    if (!committedRequests.get(key).equals(body))
                        throw new AssertionError("Idempotency body changed");
                    return committedResults.get(key).deepCopy();
                }
                if (version != body.path("expectedVersion").asInt())
                    throw new AssertionError("Ticket version conflict");
                version++;
                if (body.path("input").has("toStatus"))
                    status = body.path("input").path("toStatus").asText();
                JsonNode result =
                        AgentJson.object()
                                .put("id", 7)
                                .put("version", version)
                                .put("status", status);
                committedRequests.put(key, body.deepCopy());
                committedResults.put(key, result.deepCopy());
                if (crashAfterWrite) {
                    crashAfterWrite = false;
                    throw new AssertionError(
                            "simulated process termination after committed remote write");
                }
                return result;
            }
            if (path.equals("/internal/agent/tickets/7")) {
                ticketReads++;
                return AgentJson.object()
                        .put("id", 7)
                        .put("version", version)
                        .put("status", status);
            }
            throw new AssertionError("Unexpected outbound call: " + audience + path);
        }
    }
}
