package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

/**
 * 2100 实际失败的参数形状：evidence 1107 字，冻结上限 700，其他字段合法。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentDiagnosticCorrectionsTest {
    private static ObjectNode oversizedDiagnosis(boolean includeRepair) {
        ObjectNode args =
                AgentJson.object()
                        .put("evidence", "证".repeat(1107))
                        .put("knownFacts", "事实".repeat(213))
                        .put("candidateCauses", "候选".repeat(178))
                        .put("evidenceGaps", "缺".repeat(139))
                        .put("conclusionLevel", "SUPPORTED")
                        .put("recommendation", "建议".repeat(135) + "。")
                        .put("summary", "诊".repeat(485));
        args.putArray("evidenceIds").add("ev-existing-a").add("ev-existing-b");
        ObjectNode response =
                (ObjectNode) AgentTestSupport.response("ticket_add_analysis", args);
        if (includeRepair) {
            ArrayNode calls = (ArrayNode) response.path("assistantMessage").path("tool_calls");
            calls.addObject()
                    .put("id", "repair-call")
                    .put("type", "function")
                    .set(
                            "function",
                            AgentJson.object()
                                    .put("name", "demo_flow_restore")
                                    .put(
                                            "arguments",
                                            AgentJson.object()
                                                    .put("expectedRevision", "a".repeat(64))
                                                    .toString()));
        }
        return response;
    }

    private static void freezeOldEvidenceLimit(AgentTestSupport fixture, String id) {
        ObjectNode snapshot = fixture.store.get(id).snapshot();
        for (JsonNode tool : snapshot.path("tools")) {
            if (tool.path("function").path("name").asText().equals("ticket_add_analysis"))
                ((ObjectNode) tool.path("function").path("parameters").path("properties").path("evidence"))
                        .put("maxLength", 700);
        }
        fixture.jdbc.update("UPDATE agent_run SET snapshot_json=? WHERE id=?", snapshot.toString(), id);
    }

    @Test
    void knownOversizedDiagnosisPreservesResponseAndBudgetAndCancelsUnexecutedRepair() {
        var fixture = new AgentTestSupport();
        ObjectNode original = oversizedDiagnosis(true);
        fixture.clients.model = request -> original.deepCopy();
        String id = fixture.create("oversized-2100-shape", -1);
        freezeOldEvidenceLimit(fixture, id);
        String deadline = fixture.store.get(id).state().path("deadline").asText();
        for (int i = 0; i < 3; i++) fixture.step();
        var run = fixture.store.get(id);
        assertEquals("QUEUED", run.status());
        assertEquals(1, run.state().path("diagnosticParameterCorrections").asInt());
        assertEquals(10, run.state().path("tokens").asInt());
        assertEquals(1, run.state().path("turns").asInt());
        assertEquals(deadline, run.state().path("deadline").asText());
        assertFalse(run.state().has("modelIntent"));
        assertTrue(run.state().path("pendingCalls").isEmpty());
        assertTrue(run.state().path("referenceCorrectionPending").asBoolean());
        assertEquals(0, fixture.clients.writes.size());
        assertEquals(0, fixture.clients.actions);
        assertEquals(0, fixture.store.approvals(id).size());
        JsonNode event =
                fixture.store.events(id, 0).stream()
                        .filter(e -> e.path("type").asText().equals("DIAGNOSTIC_ARGUMENT_CORRECTION"))
                        .findFirst().orElseThrow().path("payload");
        assertEquals(original, event.path("originalModelResponse"));
        assertEquals(2, event.path("unexecutedCalls").size());
        fixture.clients.model = request -> AgentTestSupport.response("ticket_get", AgentJson.object());
        fixture.step();
        fixture.step();
        JsonNode request = fixture.clients.modelRequests.get(1);
        String messages = request.path("messages").toString();
        assertTrue(messages.contains("DIAGNOSTIC_ARGUMENTS_INVALID"));
        assertTrue(messages.contains("evidence actualLength=1107"));
        assertTrue(messages.contains("evidence maxLength=700"));
        assertTrue(messages.contains("CANCELLED_BEFORE_EXECUTION"));
        assertNotEquals(fixture.clients.modelRequests.get(0).path("callId"), request.path("callId"));
        assertEquals(20, fixture.store.get(id).state().path("tokens").asInt());
        assertEquals(0, fixture.clients.actions);
    }

    @Test
    void frozenEvidenceLimitReportsActualFieldLengthAndReachesTheNextModelTurn() {
        var fixture = new AgentTestSupport();
        fixture.clients.model = request -> {
            ObjectNode args = AgentJson.object()
                    .put("summary", "消费者关闭与业务失败")
                    .put("evidence", "证".repeat(856))
                    .put("recommendation", "审批后恢复消费者");
            args.putArray("evidenceIds").add("ev-existing-a");
            return AgentTestSupport.response("ticket_add_analysis", args);
        };
        String id = fixture.create("rabbit-2102-frozen-feedback", -1);
        freezeOldEvidenceLimit(fixture, id);
        for (int i = 0; i < 3; i++) fixture.step();
        var run = fixture.store.get(id);
        String feedback = run.state().path("messages").toString();
        assertTrue(feedback.contains("evidence actualLength=856 超出冻结 maxLength=700"));
        assertTrue(feedback.contains("evidence maxLength=700"));
        assertTrue(feedback.contains("500 字符以内"));
        assertTrue(feedback.contains("不复制完整证据包或全部指标"));
        assertEquals(0, fixture.clients.writes.size());
        fixture.step();
        fixture.step();
        JsonNode request = fixture.clients.modelRequests.get(1);
        assertTrue(request.path("messages").toString().contains("evidence actualLength=856"));
        JsonNode schema = AgentTools.frozenParameters(run, "ticket_add_analysis");
        assertEquals(700, schema.path("properties").path("evidence").path("maxLength").asInt());
        assertTrue(request.path("tools").toString().contains("\"maxLength\":700"));
        assertEquals(2, AgentDiagnosticCorrections.MAX_CORRECTIONS);
    }

    @Test
    void thirdInvalidKnownResultStopsAndResumeCannotResetCorrectionLimitOrBudget() {
        var fixture = new AgentTestSupport();
        fixture.clients.model = request -> oversizedDiagnosis(false);
        String id = fixture.create("bounded-diagnostic-corrections", -1);
        freezeOldEvidenceLimit(fixture, id);
        for (int i = 0; i < 7; i++) fixture.step();
        var run = fixture.store.get(id);
        assertEquals("NEEDS_ATTENTION", run.status());
        assertEquals(2, run.state().path("diagnosticParameterCorrections").asInt());
        assertEquals(30, run.state().path("tokens").asInt());
        assertEquals(3, fixture.clients.modelRequests.size());
        assertTrue(run.state().path("diagnosticParameterFailure").path("exhausted").asBoolean());
        assertEquals(
                oversizedDiagnosis(false),
                run.state().path("diagnosticParameterFailure").path("originalModelResponse"));
        assertThrows(com.opsagent.common.core.BusinessException.class, () -> fixture.store.resume(id, -1));
        assertEquals(0, fixture.clients.writes.size());
        assertEquals(0, fixture.clients.actions);
    }

    @Test
    void malformedRepairParametersAreNeverCorrectedOrApproved() {
        var fixture = new AgentTestSupport();
        fixture.clients.model =
                request ->
                        AgentTestSupport.response(
                                "demo_flow_restore",
                                AgentJson.object().put("expectedRevision", "invalid"));
        String id = fixture.create("reject-invalid-repair", -1);
        for (int i = 0; i < 3; i++) fixture.step();
        assertEquals("NEEDS_ATTENTION", fixture.store.get(id).status());
        assertFalse(fixture.store.get(id).state().has("diagnosticParameterCorrections"));
        assertEquals(0, fixture.clients.actions);
        assertEquals(0, fixture.store.approvals(id).size());
    }

    @Test
    void preparedWriteOrUnknownModelStateCannotEnterCorrection() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("no-uncertain-correction", -1);
        fixture.step();
        fixture.step();
        var run = fixture.store.get(id);
        ArrayNode calls = AgentJson.MAPPER.createArrayNode();
        calls.addObject().put("name", "ticket_add_analysis").put("providerCallId", "provider-call-1");
        run.state().set("toolIntent", AgentJson.object().put("preparedRequest", "existing-write"));
        assertNull(AgentDiagnosticCorrections.correct(run, oversizedDiagnosis(false), calls, "invalid"));
        run.state().remove("toolIntent");
        run.state().set("modelFailure", AgentJson.object().put("code", "MODEL_OUTCOME_UNKNOWN"));
        assertNull(AgentDiagnosticCorrections.correct(run, oversizedDiagnosis(false), calls, "invalid"));
        assertEquals(0, run.state().path("diagnosticParameterCorrections").asInt());
    }
}
