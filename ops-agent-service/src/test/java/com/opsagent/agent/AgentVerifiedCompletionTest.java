package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 已验证的工具处置无需额外模型总结，但仍必须经过显式恢复复核。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentVerifiedCompletionTest {
    @Test
    void threeTransitionsWithNoSummaryBudgetStillEnterExplicitVerifyThenComplete() {
        var fixture = new AgentTestSupport();
        String id = resolveThroughThreeTransitions(fixture, false);
        JsonNode verification =
                fixture.store.get(id).state().path("recoveryVerification").deepCopy();
        assertEquals(3, fixture.clients.writes.size());
        assertEquals(3, fixture.clients.targetReads);
        assertEquals(3, fixture.clients.ticketReads);
        assertEquals("RESOLVED", fixture.clients.status);
        assertEquals("diagnose", fixture.store.get(id).node());

        fixture.step();

        var run = fixture.store.get(id);
        assertEquals("verify", run.node());
        assertEquals("QUEUED", run.status());
        JsonNode output = run.state().path("outputs").path("diagnose");
        assertEquals("VERIFIED_TOOL_RESULT", output.path("completionSource").asText());
        assertEquals(verification, output.path("recoveryVerification"));
        assertFalse(output.has("outcome"));
        assertFalse(run.state().has("summary"));
        assertFalse(run.state().has("modelIntent"));
        assertTrue(
                fixture.store.events(id, 0).stream()
                        .anyMatch(
                                event ->
                                        event.path("type").asText().equals("NODE_COMPLETED")
                                                && event.path("payload")
                                                        .path("completionSource")
                                                        .asText()
                                                        .equals("VERIFIED_TOOL_RESULT")));

        fixture.finish(id);

        run = fixture.store.get(id);
        assertEquals("COMPLETED", run.status());
        assertEquals(1, fixture.clients.modelRequests.size());
        assertEquals(31500, run.state().path("tokens").asInt());
        assertEquals(4, fixture.clients.targetReads);
        assertEquals(4, fixture.clients.ticketReads);
        assertEquals(3, fixture.clients.writes.size());
        assertTrue(run.state().path("outputs").path("verify").path("resolved").asBoolean());
        assertEquals(verification, run.state().path("observations").path("diagnose:1:0"));
        assertFalse(run.state().has("summary"));
    }

    @Test
    void freshExplicitVerifyStillWaitsWhenAlertBecomesFiringAfterAgentResolution() {
        var fixture = new AgentTestSupport();
        String id = resolveThroughThreeTransitions(fixture, false);
        fixture.clients.episodeStatus = "firing";

        fixture.step();
        fixture.step();
        fixture.step();

        var run = fixture.store.get(id);
        assertEquals("verify", run.node());
        assertEquals("QUEUED", run.status());
        assertEquals(
                "EPISODE_PENDING",
                run.state().path("recoveryVerification").path("reasonCode").asText());
        assertFalse(run.state().path("outputs").has("verify"));
        assertEquals(4, fixture.clients.targetReads);
        assertEquals(3, fixture.clients.ticketReads);
        assertEquals(1, fixture.clients.modelRequests.size());

        fixture.clients.episodeStatus = "resolved";
        fixture.elapseRecoveryDelay(id);
        fixture.finish(id);
        assertEquals("COMPLETED", fixture.store.get(id).status());
        assertEquals(1, fixture.clients.modelRequests.size());
    }

    @Test
    void remainingNativeToolCallsAreExecutedBeforeVerifiedCompletion() {
        var fixture = new AgentTestSupport();
        String id = resolveThroughThreeTransitions(fixture, true);
        assertEquals(1, fixture.store.get(id).state().path("pendingCalls").size());

        fixture.step();

        var run = fixture.store.get(id);
        assertEquals("diagnose", run.node());
        assertEquals("ticket_get", run.state().path("toolIntent").path("name").asText());
        fixture.step();
        run = fixture.store.get(id);
        assertEquals("diagnose", run.node());
        assertEquals(4, fixture.clients.ticketReads);
        assertTrue(run.state().path("pendingCalls").isEmpty());
        assertTrue(run.state().path("observations").has("diagnose:1:1"));
        JsonNode messages = run.state().path("messages");
        assertEquals(
                "provider-call-2", messages.get(messages.size() - 1).path("tool_call_id").asText());

        fixture.finish(id);
        assertEquals("COMPLETED", fixture.store.get(id).status());
        assertEquals(1, fixture.clients.modelRequests.size());
    }

    @Test
    void registeredModelIntentStillReplaysExactlyEvenWithVerifiedRecovery() {
        var fixture = new AgentTestSupport();
        String id = resolveThroughThreeTransitions(fixture, false);
        ObjectNode state = fixture.store.get(id).state();
        JsonNode intent = fixture.clients.modelRequests.get(0).deepCopy();
        state.set("modelIntent", intent);
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);
        fixture.clients.model =
                request -> AgentJson.read("{\"outcome\":\"UNKNOWN\",\"usageKnown\":false}");

        fixture.step();

        var run = fixture.store.get(id);
        assertEquals("NEEDS_ATTENTION", run.status());
        assertEquals("diagnose", run.node());
        assertEquals(intent, fixture.clients.modelRequests.get(1));
        assertEquals(intent, run.state().path("modelIntent"));
        assertEquals(31500, run.state().path("tokens").asInt());
        assertFalse(run.state().path("outputs").has("diagnose"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unresolved", "nonterminal", "other-node", "flag-false"})
    void incompleteOrOtherNodeEvidenceCannotSkipModelBudget(String condition) {
        var fixture = new AgentTestSupport();
        String id = resolveThroughThreeTransitions(fixture, false);
        ObjectNode state = fixture.store.get(id).state();
        ObjectNode verification = (ObjectNode) state.path("recoveryVerification");
        switch (condition) {
            case "missing" -> state.remove("recoveryVerification");
            case "unresolved" -> verification.put("resolved", false);
            case "nonterminal" -> verification.put("toStatus", "PROCESSING");
            case "other-node" -> state.put("recoveryVerificationNode", "earlier-agent");
            case "flag-false" -> state.put("ticketResolved", false);
            default -> fail("Unexpected condition");
        }
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);

        fixture.step();

        assertEquals("BUDGET_EXCEEDED", fixture.store.get(id).status());
        assertEquals("diagnose", fixture.store.get(id).node());
        assertEquals(1, fixture.clients.modelRequests.size());
        assertFalse(fixture.store.get(id).state().path("outputs").has("diagnose"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LLM", "END", "OTHER_TOOL"})
    void onlyAgentFollowedDirectlyByResolveToolMayUseVerifiedCompletion(String condition) {
        var fixture = new AgentTestSupport();
        String id = resolveThroughThreeTransitions(fixture, false);
        ObjectNode snapshot = fixture.store.get(id).snapshot();
        JsonNode graph = snapshot.path("graph");
        if (condition.equals("LLM")) {
            ((ObjectNode) WorkflowGraph.node(graph, "diagnose")).put("type", "LLM");
        } else if (condition.equals("END")) {
            ((ObjectNode) WorkflowGraph.node(graph, "verify")).put("type", "END");
        } else {
            ((ObjectNode) WorkflowGraph.node(graph, "verify").path("config"))
                    .put("tool", "ticket_get");
        }
        fixture.jdbc.update(
                "UPDATE agent_run SET snapshot_json=? WHERE id=?", snapshot.toString(), id);

        fixture.step();

        assertEquals("BUDGET_EXCEEDED", fixture.store.get(id).status());
        assertEquals(1, fixture.clients.modelRequests.size());
        assertFalse(fixture.store.get(id).state().path("outputs").has("diagnose"));
    }

    private static String resolveThroughThreeTransitions(
            AgentTestSupport fixture, boolean trailingCall) {
        fixture.clients.status = "CREATED";
        fixture.clients.model =
                request -> {
                    assertEquals(
                            1,
                            fixture.clients.modelRequests.size(),
                            "No extra model summary is needed");
                    ObjectNode response =
                            (ObjectNode)
                                    AgentTestSupport.response(
                                            "ticket_resolve",
                                            AgentJson.object().put("comment", "根据业务和告警恢复证据解决工单"));
                    response.put("totalTokens", 31500);
                    if (trailingCall) {
                        ObjectNode call =
                                AgentJson.object()
                                        .put("id", "provider-call-2")
                                        .put("type", "function");
                        call.set(
                                "function",
                                AgentJson.object()
                                        .put("name", "ticket_get")
                                        .put("arguments", "{}"));
                        ((ArrayNode) response.path("assistantMessage").path("tool_calls"))
                                .add(call);
                    }
                    return response;
                };
        String id = fixture.create("verified-completion", -1);
        for (int index = 0; index < 10; index++) fixture.step();
        assertTrue(fixture.store.get(id).state().path("ticketResolved").asBoolean());
        assertEquals(
                "diagnose",
                fixture.store.get(id).state().path("recoveryVerificationNode").asText());
        assertEquals("QUEUED", fixture.store.get(id).status());
        return id;
    }
}
