package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

/**
 * @author heyu
 */
class AgentRuntimeTest {
    @Test
    void retriedModelDecisionStillCreatesOneExactApprovalAndChargesReservedUsage() {
        var fixture = new AgentTestSupport();
        fixture.clients.model = request -> {
            ObjectNode result = (ObjectNode) AgentTestSupport.response("demo_config_restore",
                    AgentJson.object().put("expectedRevision", "a".repeat(64)));
            return result.put("attempts", 2).put("usageKnown", false).put("budgetTokens", 9500);
        };
        String id = fixture.create("retried-read-only-decision", -1);
        for (int i = 0; i < 5; i++) fixture.step();
        assertEquals("WAITING_APPROVAL", fixture.store.get(id).status());
        assertEquals(0, fixture.clients.actions);
        assertEquals(9500, fixture.store.get(id).state().path("tokens").asInt());
        assertEquals(1, fixture.store.get(id).state().path("turns").asInt());
        assertEquals(1, fixture.store.approvals(id).size());
        JsonNode approval = fixture.store.approvals(id).get(0);
        fixture.store.decide(approval.path("id").asText(), 1,
                approval.path("args_hash").asText(), true, "同意", -1);
        fixture.step();
        assertEquals(1, fixture.clients.actions);
        assertEquals(1, fixture.clients.modelRequests.size());
    }

    @Test
    void failedModelDecisionHasChineseRecoveryReasonAndCannotBeResubmittedByResume() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("no-model-resubmit", -1);
        fixture.clients.model = request -> { throw AgentJson.invalid("MODEL_OUTCOME_UNKNOWN"); };
        for (int i = 0; i < 3; i++) fixture.step();
        var run = fixture.store.get(id);
        assertEquals("NEEDS_ATTENTION", run.status());
        assertFalse(run.state().path("modelFailure").path("canResume").asBoolean(true));
        assertEquals("MODEL_OUTCOME_UNKNOWN", run.state().path("modelFailure").path("code").asText());
        assertTrue(run.state().path("message").asText().contains("新的隔离演练"));
        assertThrows(com.opsagent.common.core.BusinessException.class, () -> fixture.store.resume(id, -1));
        assertEquals(0, fixture.clients.actions);
        assertEquals(1, fixture.clients.modelRequests.size());
    }

    @Test
    void legacyUnknownReceiptIsBlockedButNormalPauseAndOtherAttentionCanResume() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("legacy-model-failure", -1);
        fixture.step();
        fixture.step();
        var state = fixture.store.get(id).state();
        state.put("message", "MODEL_OUTCOME_UNKNOWN");
        fixture.jdbc.update("UPDATE agent_run SET status='NEEDS_ATTENTION',state_json=? WHERE id=?",
                state.toString(), id);
        assertEquals("MODEL_OUTCOME_UNKNOWN", AgentModelFailure.fromState(state).path("code").asText());
        assertThrows(com.opsagent.common.core.BusinessException.class, () -> fixture.store.resume(id, -1));
        state.remove("message");
        fixture.jdbc.update("UPDATE agent_run SET status='PAUSED',state_json=? WHERE id=?", state.toString(), id);
        fixture.store.resume(id, -1);
        assertEquals("QUEUED", fixture.store.get(id).status());
        state.remove("modelIntent");
        state.put("message", "工单版本冲突，请检查后继续");
        fixture.jdbc.update("UPDATE agent_run SET status='NEEDS_ATTENTION',state_json=? WHERE id=?",
                state.toString(), id);
        fixture.store.resume(id, -1);
        assertEquals("QUEUED", fixture.store.get(id).status());
    }

    @Test
    void unknownModelResultResumesSameDurableCallWithoutNewBudgetCharge() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("unknown-turn", -1);
        fixture.clients.model =
                request ->
                        AgentJson.read(
                                """
                                {"outcome":"UNKNOWN","usageKnown":false,"assistantMessage":{}}
                                """);
        fixture.step();
        fixture.step();
        fixture.step();
        assertEquals("NEEDS_ATTENTION", fixture.store.get(id).status());
        JsonNode intent = fixture.store.get(id).state().path("modelIntent").deepCopy();
        assertTrue(intent.has("callId"));
        fixture.store.resume(id, -1);
        fixture.step();
        assertEquals(intent, fixture.clients.modelRequests.get(1));
        assertEquals(1, fixture.store.get(id).state().path("turns").asInt());
        assertEquals(8000, fixture.store.get(id).state().path("tokens").asInt());
        assertEquals("NEEDS_ATTENTION", fixture.store.get(id).status());
    }

    @Test
    void lastPermittedModelTurnCanFinishNormally() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("last-turn", -1);
        fixture.step();
        var run = fixture.store.get(id);
        run.state().put("turns", 11);
        fixture.jdbc.update(
                "UPDATE agent_run SET state_json=? WHERE id=?", run.state().toString(), id);
        fixture.clients.model =
                request ->
                        AgentJson.read(
                                """
                                {"outcome":"FINAL","usageKnown":true,"totalTokens":32000,
                                 "assistantMessage":{"role":"assistant","content":"最终结论"}}
                                """);
        fixture.step();
        fixture.step();
        fixture.step();
        fixture.finish(id);
        assertEquals("COMPLETED", fixture.store.get(id).status());
        assertEquals(12, fixture.store.get(id).state().path("turns").asInt());
    }

    @Test
    void nativeLoopPersistsIntentThenPassesObservationBackToModel() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("native-loop", -1);
        fixture.step();
        fixture.step();
        assertEquals(0, fixture.clients.modelRequests.size());
        assertTrue(fixture.store.get(id).state().has("modelIntent"));
        fixture.step();
        assertEquals(
                "provider-call-1",
                fixture.store
                        .get(id)
                        .state()
                        .path("pendingCalls")
                        .get(0)
                        .path("providerCallId")
                        .asText());
        fixture.step();
        fixture.step();
        fixture.step();
        fixture.clients.model =
                request -> {
                    JsonNode messages = request.path("messages");
                    assertEquals("tool", messages.get(messages.size() - 1).path("role").asText());
                    assertEquals(
                            "provider-call-1",
                            messages.get(messages.size() - 1).path("tool_call_id").asText());
                    return AgentJson.read(
                            """
                            {"outcome":"FINAL","usageKnown":true,"totalTokens":10,
                             "assistantMessage":{"role":"assistant","content":"已依据真实工具观测完成诊断"}}
                            """);
                };
        fixture.step();
        fixture.step();
        fixture.finish(id);
        assertEquals("COMPLETED", fixture.store.get(id).status());
        assertEquals(2, fixture.clients.modelRequests.size());
    }

    @Test
    void highRiskCallWaitsForExactApprovalAndExecutesSameIntent() {
        var fixture = new AgentTestSupport();
        fixture.clients.model =
                request ->
                        AgentTestSupport.response(
                                "demo_config_restore",
                                AgentJson.object().put("expectedRevision", "a".repeat(64)));
        String id = fixture.create("approval-loop", -1);
        for (int i = 0; i < 5; i++) fixture.step();
        assertEquals("WAITING_APPROVAL", fixture.store.get(id).status());
        assertEquals(0, fixture.clients.actions);
        JsonNode approval = fixture.store.approvals(id).get(0);
        JsonNode intent = fixture.store.get(id).state().path("toolIntent").deepCopy();
        fixture.store.decide(
                approval.path("id").asText(),
                1,
                approval.path("args_hash").asText(),
                true,
                "同意",
                -1);
        fixture.step();
        assertEquals(1, fixture.clients.actions);
        assertTrue(
                fixture.store.get(id).state().path("observations").has(intent.path("id").asText()));
    }

    @Test
    void expiredApprovalCannotExecuteAction() {
        var fixture = new AgentTestSupport();
        fixture.clients.model =
                request ->
                        AgentTestSupport.response(
                                "demo_config_restore",
                                AgentJson.object().put("expectedRevision", "a".repeat(64)));
        String id = fixture.create("expired-approval", -1);
        for (int i = 0; i < 5; i++) fixture.step();
        JsonNode approval = fixture.store.approvals(id).get(0);
        fixture.store.decide(
                approval.path("id").asText(),
                1,
                approval.path("args_hash").asText(),
                true,
                "同意",
                -1);
        fixture.jdbc.update("UPDATE agent_approval SET expires_at=TIMESTAMPADD(SECOND,-1,NOW(3))");
        fixture.step();
        assertEquals("REJECTED", fixture.store.get(id).status());
        assertEquals(0, fixture.clients.actions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket_add_analysis", "ticket_resolve"})
    void remoteWriteCrashReplaysPersistedExactBodyWithoutReadingNewVersion(String tool) {
        var fixture = new AgentTestSupport();
        ObjectNode args =
                tool.equals("ticket_resolve")
                        ? AgentJson.object().put("comment", "探针与告警已恢复")
                        : AgentJson.object()
                                .put("summary", "故障原因")
                                .put("evidence", "实际探针")
                                .put("recommendation", "恢复固定基线");
        fixture.clients.model = request -> AgentTestSupport.response(tool, args);
        String id = fixture.create("write-crash", -1);
        for (int i = 0; i < 5; i++) fixture.step();
        JsonNode intent = fixture.store.get(id).state().path("toolIntent");
        assertEquals(
                1, intent.path("preparedRequest").path("body").path("expectedVersion").asInt());
        assertEquals(0, fixture.clients.writes.size());
        fixture.clients.crashAfterWrite = true;
        assertThrows(AssertionError.class, fixture::step);
        assertEquals(2, fixture.clients.version);
        fixture.recoverLease();
        fixture.step();
        assertEquals(2, fixture.clients.writes.size());
        assertEquals(fixture.clients.writes.get(0), fixture.clients.writes.get(1));
        assertEquals(1, fixture.clients.ticketReads);
        assertFalse(fixture.store.get(id).state().has("toolIntent"));
    }

    @Test
    void resolveRejectsUnrelatedIncidentAndFutureEvidence() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("evidence", -1);
        var run = fixture.store.get(id);
        ObjectNode call = AgentJson.object().put("name", "ticket_resolve").put("id", "test-call");
        call.set("arguments", AgentJson.object().put("comment", "已恢复"));
        var tools = new AgentTools(fixture.clients);
        fixture.clients.incident = "another-incident";
        assertFalse(
                tools.prepare(run, call, fixture.clients.fromState(run.state()))
                        .path("observation")
                        .path("resolved")
                        .asBoolean());
        fixture.clients.incident = "incident-1";
        fixture.clients.observedAt = Instant.now().plusSeconds(60).toString();
        assertFalse(tools.prepare(run, call, fixture.clients.fromState(run.state())).has("body"));
        fixture.clients.observedAt = Instant.now().toString();
        fixture.clients.recoverySource = "TTL_GUARD";
        assertFalse(tools.prepare(run, call, fixture.clients.fromState(run.state())).has("body"));
        assertEquals(0, fixture.clients.ticketReads);
    }
}
