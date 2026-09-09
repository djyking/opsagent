package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens.Context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

/**
 * 已批准修复的真实执行回执只触发验证交接，恢复门禁和原生意图仍完整执行。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentApprovedRepairHandoffTest {
    @Test
    void approvedRepairWithEightToolsHandsOffWithoutFifthModelRoundAndWaitsForRealRecovery() {
        RepairClients clients = new RepairClients();
        var fixture = new AgentTestSupport(clients);
        String id = waitingApproval(fixture);
        approve(fixture, id);
        fixture.step();
        JsonNode receipt = fixture.store.get(id).state().path("approvedRepair").deepCopy();
        assertFalse(receipt.isMissingNode());
        assertEquals(1, clients.actions);
        assertEquals(1, fixture.store.get(id).state().path("pendingCalls").size());

        fixture.step();
        assertEquals(
                "demo_target_inspect",
                fixture.store.get(id).state().path("toolIntent").path("name").asText());
        fixture.step();
        assertEquals(8, fixture.store.get(id).state().path("toolCount").asInt());
        assertEquals(4, fixture.store.get(id).state().path("turns").asInt());
        assertEquals(
                17580 + 2 * com.opsagent.common.core.QueryEmbeddingBudget.reserve("Redis连接故障如何核验"),
                fixture.store.get(id).state().path("tokens").asInt());
        assertEquals(1, fixture.store.get(id).state().path("toolAiReservations").size());
        fixture.step();

        var run = fixture.store.get(id);
        assertEquals("verify", run.node());
        JsonNode output = run.state().path("outputs").path("diagnose");
        assertEquals(
                "APPROVED_REPAIR_REQUIRES_VERIFICATION", output.path("completionSource").asText());
        assertEquals("PENDING", output.path("verificationStatus").asText());
        assertEquals(receipt, output.path("approvedRepair"));
        assertFalse(run.state().path("ticketResolved").asBoolean());
        assertFalse(run.state().has("summary"));
        assertFalse(output.has("outcome"));

        fixture.step();
        fixture.step();
        assertEquals(
                "BUSINESS_PENDING",
                fixture.store
                        .get(id)
                        .state()
                        .path("recoveryVerification")
                        .path("reasonCode")
                        .asText());
        assertEquals(1, clients.writes.size(), "Only committed diagnosis, no ticket transition");
        clients.successes = 3;
        clients.episodeStatus = "firing";
        fixture.elapseRecoveryDelay(id);
        fixture.step();
        assertEquals(
                "EPISODE_PENDING",
                fixture.store
                        .get(id)
                        .state()
                        .path("recoveryVerification")
                        .path("reasonCode")
                        .asText());
        assertFalse(fixture.store.get(id).state().path("ticketResolved").asBoolean());

        clients.episodeStatus = "resolved";
        fixture.elapseRecoveryDelay(id);
        fixture.finish(id);
        assertEquals("COMPLETED", fixture.store.get(id).status());
        assertEquals("RESOLVED", clients.status);
        assertEquals(4, clients.modelRequests.size());
        assertEquals(4, clients.writes.size(), "Diagnosis plus three real versioned transitions");
        assertEquals(1, clients.actions);
        fixture.store.notifyRun(id);
        fixture.step();
        assertEquals(1, clients.actions);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-diagnosis",
                "other-diagnosis",
                "rejected-action",
                "wrong-incident",
                "wrong-target",
                "manual",
                "ttl",
                "not-applied",
                "revision",
                "invalid-observation"
            })
    void incompleteOrUnrelatedActionNeverCreatesHandoffReceipt(String condition) {
        RepairClients clients = new RepairClients();
        var fixture = new AgentTestSupport(clients);
        String id = waitingApproval(fixture);
        ObjectNode state = fixture.store.get(id).state();
        switch (condition) {
            case "missing-diagnosis" -> state.remove("diagnosis");
            case "other-diagnosis" -> state.put("diagnosisNode", "earlier");
            case "rejected-action" -> clients.repair.put("actionAccepted", false);
            case "wrong-incident" -> clients.repair.put("incidentId", "another-incident");
            case "wrong-target" -> clients.repair.put("targetCode", AgentTargets.NOTIFICATION);
            case "manual" -> clients.repair.put("recoverySource", "MANUAL");
            case "ttl" -> clients.repair.put("recoverySource", "TTL_GUARD");
            case "not-applied" -> clients.repair.put("configurationStatus", "PENDING");
            case "revision" -> clients.repair.put("appliedRevision", "c".repeat(64));
            case "invalid-observation" -> clients.repair.put("observedAt", "unknown");
            default -> fail("Unknown condition");
        }
        persist(fixture, id, state);
        approve(fixture, id);
        fixture.step();
        assertFalse(fixture.store.get(id).state().has("approvedRepair"));
        fixture.step();
        fixture.step();
        exhaustSummaryBudget(fixture, id);
        fixture.step();
        assertEquals("BUDGET_EXCEEDED", fixture.store.get(id).status());
        assertEquals("diagnose", fixture.store.get(id).node());
        assertFalse(fixture.store.get(id).state().path("ticketResolved").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVAL", "RAG", "CONDITION", "PROMPT"})
    void customFrozenGraphRemainsSubjectToItsOwnAgentAndDownstreamNodes(String custom) {
        RepairClients clients = new RepairClients();
        var fixture = new AgentTestSupport(clients);
        String id = waitingApproval(fixture);
        approve(fixture, id);
        fixture.step();
        fixture.step();
        fixture.step();
        ObjectNode snapshot = fixture.store.get(id).snapshot();
        JsonNode graph = snapshot.path("graph");
        if (custom.equals("PROMPT")) {
            ((ObjectNode) WorkflowGraph.node(graph, "diagnose").path("config"))
                    .put("prompt", "必须完成自定义知识核验");
        } else {
            ((ArrayNode) graph.path("nodes"))
                    .add(AgentJson.object().put("id", "extra").put("type", custom));
            for (JsonNode edge : graph.path("edges")) {
                if (edge.path("from").asText().equals("diagnose"))
                    ((ObjectNode) edge).put("to", "extra");
            }
            ((ArrayNode) graph.path("edges"))
                    .add(AgentJson.object().put("from", "extra").put("to", "verify"));
        }
        fixture.jdbc.update(
                "UPDATE agent_run SET snapshot_json=? WHERE id=?", snapshot.toString(), id);
        exhaustSummaryBudget(fixture, id);
        fixture.step();
        assertEquals("BUDGET_EXCEEDED", fixture.store.get(id).status());
        assertEquals("diagnose", fixture.store.get(id).node());
        assertFalse(fixture.store.get(id).state().path("outputs").has("diagnose"));
    }

    @Test
    void registeredUnknownModelIntentIsNotSkippedEvenAfterApprovedRepair() {
        RepairClients clients = new RepairClients();
        var fixture = new AgentTestSupport(clients);
        String id = waitingApproval(fixture);
        approve(fixture, id);
        fixture.step();
        fixture.step();
        fixture.step();
        ObjectNode state = fixture.store.get(id).state();
        JsonNode intent = clients.modelRequests.get(3).deepCopy();
        state.set("modelIntent", intent);
        persist(fixture, id, state);
        clients.model =
                request -> AgentJson.object().put("outcome", "UNKNOWN").put("usageKnown", false);
        fixture.step();
        assertEquals("NEEDS_ATTENTION", fixture.store.get(id).status());
        assertEquals(intent, clients.modelRequests.get(4));
        assertEquals(intent, fixture.store.get(id).state().path("modelIntent"));
        assertFalse(fixture.store.get(id).state().path("outputs").has("diagnose"));
    }

    @Test
    void committedRepairCrashReplaysSameApprovedActionAndPersistsReceiptWithNativeResult() {
        RepairClients clients = new RepairClients();
        var fixture = new AgentTestSupport(clients);
        String id = waitingApproval(fixture);
        approve(fixture, id);
        JsonNode intent = fixture.store.get(id).state().path("toolIntent").deepCopy();
        clients.crashAfterAction = true;
        assertThrows(AssertionError.class, fixture::step);
        assertFalse(fixture.store.get(id).state().has("approvedRepair"));
        assertEquals(intent, fixture.store.get(id).state().path("toolIntent"));
        fixture.recoverLease();
        clients.repair.put("observedAt", Instant.now().minusSeconds(90).toString());
        fixture.step();
        assertEquals(2, clients.actionAttempts);
        assertEquals(1, clients.actions);
        assertEquals(clients.firstAction, clients.lastAction);
        assertTrue(fixture.store.get(id).state().has("approvedRepair"));
        assertEquals(1, fixture.store.get(id).state().path("pendingCalls").size());
        fixture.step();
        fixture.step();
        exhaustSummaryBudget(fixture, id);
        fixture.step();
        assertEquals("verify", fixture.store.get(id).node());
    }

    private static String waitingApproval(AgentTestSupport fixture) {
        fixture.clients.status = "CREATED";
        fixture.clients.model =
                request -> {
                    int round = fixture.clients.modelRequests.size();
                    ObjectNode response =
                            switch (round) {
                                case 1 ->
                                        batch(
                                                "ticket_get",
                                                AgentJson.object(),
                                                "demo_target_inspect",
                                                AgentJson.object());
                                case 2 ->
                                        batch(
                                                "recent_changes",
                                                AgentJson.object(),
                                                "knowledge_search",
                                                AgentJson.object().put("query", "Redis连接故障如何核验"));
                                case 3 ->
                                        batch(
                                                "demo_target_inspect",
                                                AgentJson.object(),
                                                "ticket_add_analysis",
                                                AgentJson.object()
                                                        .put("summary", "业务探针连接失败，近期配置变更是待核验候选原因")
                                                        .put("evidence", "配置版本与连接失败探针已记录")
                                                        .put("recommendation", "申请恢复当前版本对应的受控配置基线")
                                                        .put("knownFacts", "实际探针失败")
                                                        .put("candidateCauses", "连接配置发生改变")
                                                        .put("evidenceGaps", "需修复后重测并等待告警恢复"));
                                case 4 ->
                                        batch(
                                                "demo_config_restore",
                                                AgentJson.object()
                                                        .put("expectedRevision", "a".repeat(64)),
                                                "demo_target_inspect",
                                                AgentJson.object());
                                default ->
                                        throw new AssertionError(
                                                "No fifth model decision is needed after approved"
                                                        + " repair");
                            };
                    response.put("totalTokens", new int[] {1609, 2746, 4130, 9095}[round - 1]);
                    return response;
                };
        String id = fixture.create("approved-repair-handoff", -1);
        for (int i = 0; i < 40 && fixture.store.get(id).status().equals("QUEUED"); i++)
            fixture.step();
        assertEquals("WAITING_APPROVAL", fixture.store.get(id).status());
        assertEquals(0, fixture.clients.actions);
        assertTrue(fixture.store.get(id).state().has("diagnosis"));
        return id;
    }

    private static ObjectNode batch(
            String first, JsonNode firstArgs, String second, JsonNode secondArgs) {
        ObjectNode response = (ObjectNode) AgentTestSupport.response(first, firstArgs);
        ObjectNode extra =
                (ObjectNode)
                        AgentTestSupport.response(second, secondArgs)
                                .path("assistantMessage")
                                .path("tool_calls")
                                .get(0);
        extra.put("id", "provider-call-2");
        ((ArrayNode) response.path("assistantMessage").path("tool_calls")).add(extra);
        return response;
    }

    private static void approve(AgentTestSupport fixture, String id) {
        JsonNode approval = fixture.store.approvals(id).get(0);
        fixture.store.decide(
                approval.path("id").asText(),
                approval.path("revision").asInt(),
                approval.path("args_hash").asText(),
                true,
                "同意精确修复",
                -1);
    }

    private static void exhaustSummaryBudget(AgentTestSupport fixture, String id) {
        ObjectNode state = fixture.store.get(id).state();
        state.put("tokens", 31500);
        persist(fixture, id, state);
    }

    private static void persist(AgentTestSupport fixture, String id, ObjectNode state) {
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);
    }

    /**
     * @author heyu
     */
    private static class RepairClients extends AgentTestSupport.FakeClients {
        final ObjectNode repair =
                AgentJson.object()
                        .put("scope", "ISOLATED_DEMO")
                        .put("actionAccepted", true)
                        .put("targetCode", AgentTargets.ORDER)
                        .put("incidentId", "incident-1")
                        .put("status", "BASELINE")
                        .put("configurationStatus", "APPLIED")
                        .put("recoverySource", "AGENT_TOOL")
                        .put("expectedRevision", "b".repeat(64))
                        .put("appliedRevision", "b".repeat(64))
                        .put("observedAt", Instant.now().toString());
        int successes = 1;
        int actionAttempts;
        boolean crashAfterAction;
        JsonNode firstAction;
        JsonNode lastAction;

        @Override
        JsonNode call(String audience, String path, String method, JsonNode body, Context actor) {
            if (path.endsWith("/actions")) {
                actionAttempts++;
                lastAction = body.deepCopy();
                if (firstAction == null) {
                    firstAction = body.deepCopy();
                    actions++;
                } else
                    assertEquals(
                            firstAction,
                            body,
                            "Remote action must replay exact persisted idempotency key and"
                                    + " arguments");
                if (crashAfterAction) {
                    crashAfterAction = false;
                    throw new AssertionError("Crash after accepted remote repair");
                }
                return repair.deepCopy();
            }
            if (path.endsWith("/evidence"))
                return AgentJson.object()
                        .put("incidentId", "incident-1")
                        .put("targetCode", AgentTargets.ORDER);
            if (path.equals("/internal/rag/search"))
                return AgentJson.object().putArray("citations");
            JsonNode result = super.call(audience, path, method, body, actor);
            if (path.endsWith("/snapshot"))
                ((ObjectNode) result.path("business")).put("consecutiveSuccesses", successes);
            return result;
        }
    }
}
