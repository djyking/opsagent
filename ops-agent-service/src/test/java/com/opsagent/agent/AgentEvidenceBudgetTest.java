package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * 2075 实际未发送请求的离线预算复现；不重置额度、不调用模型。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentEvidenceBudgetTest {
    @Test
    void mappingForLatestSampleCannotReplaceBodyOfAnEarlierOrUnregisteredSample() {
        String tool = "demo_target_inspect";
        String prefix = "工具=" + tool + "；字段有省略，原文 observations/";
        ObjectNode request = AgentJson.object();
        var messages = request.putArray("messages");
        messages.add(
                AgentJson.object()
                        .put("role", "system")
                        .put(
                                "content",
                                AgentEvidenceRegistry.MAPPING_PREFIX
                                        + "run=bound\ntool="
                                        + tool
                                        + ";observations/current\n"
                                        + "ev-current | @2026-09-07T15:26:44Z |"
                                        + " READY/true|{\"httpStatus\":503}\n"));
        ObjectNode older =
                AgentJson.object()
                        .put("role", "tool")
                        .put("tool_call_id", "old-provider")
                        .put(
                                "content",
                                prefix
                                        + "older。\nexpectedRevision="
                                        + "a".repeat(64)
                                        + "\nbusiness.httpStatus=200");
        ObjectNode current =
                AgentJson.object()
                        .put("role", "tool")
                        .put("tool_call_id", "current-provider")
                        .put(
                                "content",
                                prefix
                                        + "current。\nexpectedRevision="
                                        + "b".repeat(64)
                                        + "\nbusiness.httpStatus=503");
        ObjectNode unmapped =
                AgentJson.object()
                        .put("role", "tool")
                        .put("tool_call_id", "foreign-provider")
                        .put(
                                "content",
                                prefix
                                        + "current-extra。\n"
                                        + "incidentId=foreign\n"
                                        + "business.httpStatus=429");
        messages.add(older).add(current).add(unmapped);
        JsonNode oldCopy = older.deepCopy();
        JsonNode unmappedCopy = unmapped.deepCopy();
        AgentContext.compactMappedMessages(request);
        assertEquals(oldCopy, older);
        assertEquals(unmappedCopy, unmapped);
        assertFalse(current.path("content").asText().contains("business.httpStatus=503"));
        assertTrue(current.path("content").asText().contains("expectedRevision=" + "b".repeat(64)));
        assertEquals("current-provider", current.path("tool_call_id").asText());
    }

    @Test
    void actualUnsent2075RequestFitsOriginalRemainingBudgetWithNativeCallsAndMappingIntact()
            throws Exception {
        JsonNode sample =
                AgentJson.MAPPER.readTree(
                        getClass().getResourceAsStream("/evidence/2075-context-budget.json"));
        ObjectNode snapshot = AgentJson.object();
        snapshot.set("tools", sample.path("tools"));
        var run =
                new AgentStore.Run(
                        "7cf6c09c-fc1f-4112-9666-005d315f8a6f",
                        1,
                        "BUDGET_EXCEEDED",
                        "diagnose",
                        snapshot,
                        (ObjectNode) sample.path("state"),
                        1,
                        false,
                        false,
                        Instant.parse(sample.path("createdAt").asText()));
        JsonNode originalMessages = run.state().path("messages").deepCopy();
        JsonNode originalTools = snapshot.path("tools").deepCopy();
        assertTrue(AgentRuntime.resumableContextBudget(run));
        ObjectNode request =
                AgentJson.object()
                        .put("remainingTokens", 40000 - 25841)
                        .put("maxOutputTokens", 4096);
        request.set("messages", originalMessages.deepCopy());
        request.set("tools", snapshot.path("tools"));
        AgentEvidenceRegistry.attachMapping(run, request);
        String mapping = AgentEvidenceRegistry.sentMappingAudit(request).path("mapping").asText();
        long before = AgentContext.inputUpperBound(request);
        boolean fits = AgentContext.fitNewRequest(request);
        long after = AgentContext.inputUpperBound(request);
        assertTrue(fits, "input=" + after + ", before=" + before + ", remaining=14159");
        assertTrue(after + request.path("maxOutputTokens").asInt() <= 14159);
        assertEquals(originalMessages, run.state().path("messages"));
        assertEquals(originalTools, run.snapshot().path("tools"));
        assertEquals(
                mapping, AgentEvidenceRegistry.sentMappingAudit(request).path("mapping").asText());
        assertEquals(originalMessages.size(), request.path("messages").size());
        for (int i = 0; i < originalMessages.size(); i++) {
            assertEquals(
                    originalMessages.path(i).path("tool_calls"),
                    request.path("messages").path(i).path("tool_calls"));
            assertEquals(
                    originalMessages.path(i).path("tool_call_id"),
                    request.path("messages").path(i).path("tool_call_id"));
        }
        for (int i = 0; i < originalTools.size(); i++) {
            assertEquals(
                    originalTools.path(i).path("function").path("name"),
                    request.path("tools").path(i).path("function").path("name"));
            assertEquals(
                    originalTools.path(i).path("function").path("parameters"),
                    request.path("tools").path(i).path("function").path("parameters"));
        }
        assertEquals(25841, run.state().path("tokens").asInt());
        assertEquals(40000, run.state().path("tokenBudget").asInt());
    }
}
