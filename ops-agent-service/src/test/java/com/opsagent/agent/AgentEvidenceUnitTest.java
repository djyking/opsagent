package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * 2100 实际百分数的最小离线样本；单位与测量配对，元数据不能充当实测依据。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentEvidenceUnitTest {
    @Test
    void actualPercentageKeepsItsOriginalUnitAndValueThroughRequestCompression() {
        var run = sample(false);
        JsonNode original = run.state().path("observations").deepCopy();
        JsonNode entry = AgentEvidenceRegistry.currentEntries(run).get(0);
        assertEquals(1.6092428054075611, entry.path("facts").path("metrics.errorRate.value").asDouble());
        assertEquals("%", entry.path("units").path("metrics.errorRate.unit").asText());
        assertEquals("requests/s", entry.path("units").path("metrics.rps.unit").asText());
        assertEquals("ms", entry.path("units").path("metrics.p95Ms.unit").asText());
        assertFalse(entry.path("facts").has("metrics.p95Ms.value"));

        ObjectNode request =
                AgentJson.object().put("remainingTokens", 40000).put("maxOutputTokens", 4096);
        request.set("tools", run.snapshot().path("tools"));
        var messages = request.putArray("messages");
        messages.add(AgentJson.object().put("role", "system").put("content", "固定权限"));
        messages.add(AgentJson.object().put("role", "user").put("content", "核对原始测量"));
        JsonNode assistant =
                AgentTestSupport.response("observability_evidence", AgentJson.object())
                        .path("assistantMessage");
        messages.add(assistant.deepCopy());
        messages.add(
                AgentJson.object()
                        .put("role", "tool")
                        .put("tool_call_id", assistant.path("tool_calls").path(0).path("id").asText())
                        .put("content", "完整观测保存在服务端。".repeat(7000)));
        AgentEvidenceRegistry.attachMapping(run, request);
        String mapping = AgentEvidenceRegistry.mapping(run);
        assertTrue(AgentContext.inputUpperBound(request) > 40000);
        assertTrue(AgentContext.fitNewRequest(request));
        String sent = AgentEvidenceRegistry.sentMappingAudit(request).path("mapping").asText();
        assertEquals(mapping, sent);
        assertTrue(sent.contains("\"metrics.errorRate.value\":1.6092428054075611"));
        assertTrue(sent.contains("\"metrics.errorRate.unit\":\"%\""));
        assertFalse(sent.contains("160.9"));
        assertEquals(original, run.state().path("observations"));
    }

    @Test
    void unitTextCannotSatisfyTheRequiredMeasuredFact() {
        var run = sample(false);
        JsonNode entry = AgentEvidenceRegistry.currentEntries(run).get(0);
        ObjectNode args = diagnosis(entry.path("id").asText());
        args.put("evidence", "metrics.errorRate.unit=%，其余指标 unit=requests/s，unit=ms");
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, args));
        args.put("evidence", "metrics.errorRate.value=1.6092428054075611 %");
        assertDoesNotThrow(() -> AgentEvidenceRegistry.validate(run, args));
    }

    @Test
    void missingMeasurementDoesNotBecomeUsableMerelyBecauseItsUnitIsKnown() {
        var run = sample(true);
        JsonNode entry = AgentEvidenceRegistry.currentEntries(run).get(0);
        assertEquals("%", entry.path("units").path("metrics.errorRate.unit").asText());
        assertTrue(entry.path("facts").isEmpty());
        assertFalse(entry.path("usable").asBoolean());
        ObjectNode args = diagnosis(entry.path("id").asText()).put("evidence", "unit=%");
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, args));
    }

    private static ObjectNode diagnosis(String id) {
        ObjectNode args = AgentJson.object().put("conclusionLevel", "SUPPORTED");
        args.putArray("evidenceIds").add(id);
        return args;
    }

    private static AgentStore.Run sample(boolean missing) {
        Instant now = Instant.now();
        ObjectNode state =
                AgentJson.object()
                        .put("targetCode", AgentTargets.ORDER)
                        .put("incidentId", "unit-sample")
                        .put("ticketId", 2100)
                        .put("deadline", now.plusSeconds(900).toString());
        ObjectNode snapshot = AgentJson.object();
        snapshot.set("tools", AgentTools.schemas(AgentTargets.ORDER));
        var run =
                new AgentStore.Run(
                        "unit-run", 1, "RUNNING", "diagnose", snapshot, state, 1, false, false,
                        now.minusSeconds(1));
        ObjectNode result =
                AgentJson.object()
                        .put("service", AgentTargets.ORDER)
                        .put("environment", "DEMO")
                        .put("ticketId", 2100)
                        .put("collectedAt", now.toString());
        ObjectNode entry =
                result.putArray("entries").addObject()
                        .put("id", "evidence:observation:unit-sample")
                        .put("observedAt", now.toString())
                        .put("quality", "READY");
        ObjectNode metrics = entry.putObject("data").putObject("metrics");
        ObjectNode errorRate = metrics.putObject("errorRate").put("unit", "%");
        if (missing) errorRate.putNull("value");
        else {
            errorRate.put("value", 1.6092428054075611);
            metrics.putObject("rps").put("value", 1.1619484486691893).put("unit", "requests/s");
            metrics.putObject("p95Ms").putNull("value").put("unit", "ms");
        }
        state.putObject("observations").set("diagnose:1:3", result);
        AgentEvidenceRegistry.register(
                run,
                AgentJson.object().put("id", "diagnose:1:3").put("name", "observability_evidence"),
                result);
        return run;
    }
}
