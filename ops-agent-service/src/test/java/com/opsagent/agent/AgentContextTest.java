package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens.Context;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 跨层预算合同、完整证据保留及原生调用配对回归，不请求真实模型。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentContextTest {
    @Test
    void targetProjectionKeepsExactRevisionAndDoesNotMutateDurableEvidence() {
        ObjectNode result =
                AgentJson.object()
                        .put("expectedRevision", "a".repeat(64))
                        .put("appliedRevision", "b".repeat(64))
                        .put("incidentId", "incident-1")
                        .put("status", "FAULT_ACTIVE")
                        .put("scenarioCode", "NACOS_REDIS_CONFIG_DRIFT")
                        .put("configurationStatus", "APPLIED")
                        .put("password", "NEVER_IN_MODEL_CONTEXT")
                        .put("unneededDiagnostics", "完整服务端诊断".repeat(3000));
        result.set(
                "business",
                AgentJson.object()
                        .put("httpStatus", 503)
                        .put("reasonCode", "REDIS_UNAVAILABLE")
                        .put("consecutiveSuccesses", 0));
        JsonNode original = result.deepCopy();
        JsonNode call =
                AgentJson.object().put("id", "diagnose:3:0").put("name", "demo_target_inspect");

        String projected = AgentContext.project(call, result);

        assertEquals(original, result);
        assertEquals(projected, AgentContext.project(call, result));
        assertTrue(projected.length() <= 1200);
        assertTrue(projected.contains("expectedRevision=" + "a".repeat(64)));
        assertTrue(projected.contains("business.httpStatus=503"));
        assertTrue(projected.contains("observations/diagnose:3:0"));
        assertFalse(projected.contains("NEVER_IN_MODEL_CONTEXT"));
        assertFalse(projected.contains("完整服务端诊断"));
    }

    @Test
    void knowledgeProjectionKeepsThreeBoundedSourceMappingsRatherThanDuplicatingFullCitations() {
        ObjectNode result = AgentJson.object();
        ArrayNode citations = result.putArray("citations");
        StringBuilder evidence = new StringBuilder();
        for (int index = 1; index <= 4; index++) {
            citations.add(
                    AgentJson.object()
                            .put("sourceId", "S" + index)
                            .put("documentId", 100 + index)
                            .put("chunkId", 200 + index)
                            .put("documentName", "手册" + index)
                            .put("extraMetadata", "不需要送给模型的元数据".repeat(100)));
            evidence.append("[S")
                    .append(index)
                    .append("]\n文档：手册")
                    .append(index)
                    .append("\n章节：检查\n页码：1\n正文：证据")
                    .append(index)
                    .append("中文故障处理证据".repeat(100))
                    .append("\n\n");
        }
        result.put("evidence", evidence.toString());
        JsonNode original = result.deepCopy();

        String projected =
                AgentContext.project(
                        AgentJson.object()
                                .put("name", "knowledge_search")
                                .put("id", "diagnose:2:0"),
                        result);

        assertEquals(original, result);
        assertTrue(projected.length() <= 1200);
        for (int index = 1; index <= 3; index++) {
            assertTrue(
                    projected.contains(
                            "[S" + index + "] doc=" + (100 + index) + " chunk=" + (200 + index)));
            assertTrue(projected.contains("证据" + index));
        }
        assertFalse(projected.contains("[S4]"));
        assertFalse(projected.contains("不需要送给模型的元数据"));
        projected
                .lines()
                .filter(line -> line.startsWith("[S"))
                .forEach(line -> assertTrue(line.length() <= 350));
    }

    @Test
    void byteBudgetMatchesNativeClientUtf8EnvelopeIncludingMissingToolDescription()
            throws Exception {
        ArrayNode messages =
                AgentJson.MAPPER
                        .createArrayNode()
                        .add(AgentJson.object().put("role", "user").put("content", "中文🚀\"\\\n"));
        JsonNode parameters = AgentJson.read("{\"type\":\"object\",\"properties\":{}}");
        ObjectNode request = AgentJson.object();
        request.set("messages", messages);
        ObjectNode function = AgentJson.object().put("name", "inspect");
        function.set("parameters", parameters);
        ObjectNode tool = AgentJson.object().put("type", "function");
        tool.set("function", function);
        request.putArray("tools").add(tool);
        var normalized =
                java.util.List.of(
                        Map.of(
                                "type",
                                "function",
                                "function",
                                Map.of(
                                        "name",
                                        "inspect",
                                        "description",
                                        "",
                                        "parameters",
                                        parameters)));
        long ragBound =
                AgentJson.MAPPER
                                .writeValueAsString(
                                        Map.of("messages", messages, "tools", normalized))
                                .getBytes(StandardCharsets.UTF_8)
                                .length
                        + 512L
                        + messages.size() * 64L;

        assertEquals(ragBound, AgentContext.inputUpperBound(request));
    }

    @Test
    void compactsOldToolBodiesWithinRemainingBudgetWithoutChangingNativeMessagesOrPairing() {
        ArrayNode original = history(4);
        ObjectNode request = request(original.deepCopy(), 19018);
        assertTrue(AgentContext.inputUpperBound(request) + 1800 > 19018);

        assertTrue(AgentContext.fitNewRequest(request));
        assertTrue(AgentContext.inputUpperBound(request) <= 32768);
        assertTrue(AgentContext.inputUpperBound(request) + 1800 <= 19018);
        assertEquals(original.size(), request.path("messages").size());
        Set<String> pending = new HashSet<>();
        boolean compressed = false;
        for (int index = 0; index < original.size(); index++) {
            JsonNode before = original.path(index);
            JsonNode after = request.path("messages").path(index);
            if (before.path("role").asText().equals("tool")) {
                assertEquals(before.path("tool_call_id"), after.path("tool_call_id"));
                assertTrue(pending.remove(after.path("tool_call_id").asText()));
                if (!before.equals(after)) {
                    compressed = true;
                    assertTrue(after.path("content").asText().contains("已截断"));
                    assertTrue(after.path("content").asText().contains("持久化"));
                }
            } else {
                assertTrue(pending.isEmpty());
                assertEquals(before, after);
                for (JsonNode call : before.path("tool_calls"))
                    pending.add(call.path("id").asText());
            }
        }
        assertTrue(pending.isEmpty());
        assertTrue(compressed);
    }

    @Test
    void budgetCompactionRetainsLatestProbeRevisionAcrossSubsequentKnowledgeTurns() {
        ArrayNode messages = history(4);
        ((ObjectNode) messages.path(2).path("tool_calls").path(0).path("function"))
                .put("name", "demo_target_inspect");
        String revision = "a".repeat(64);
        String observation =
                AgentContext.project(
                        AgentJson.object().put("name", "demo_target_inspect").put("id", "probe"),
                        AgentJson.object()
                                .put("expectedRevision", revision)
                                .put("incidentId", "incident-1"));
        ((ObjectNode) messages.path(3)).put("content", observation + "详细只读证据".repeat(200));
        ObjectNode request = request(messages.deepCopy(), 16800);
        assertTrue(AgentContext.fitNewRequest(request));
        assertTrue(
                request.path("messages")
                        .path(3)
                        .path("content")
                        .asText()
                        .contains("expectedRevision=" + revision));
        assertEquals("call-0-0", request.path("messages").path(3).path("tool_call_id").asText());
        assertTrue(AgentContext.inputUpperBound(request) + 1800 <= 16800);
    }

    @Test
    void runtimeFitsNewIntentButKeepsStoredObservationAndConversationEvidenceIntact() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("context-budget", -1);
        fixture.step();
        ObjectNode state = fixture.store.get(id).state();
        ArrayNode originalMessages = history(4);
        state.set("messages", originalMessages);
        state.put("turns", 4).put("tokens", 12982);
        ObjectNode evidence = AgentJson.object().put("raw", "完整中文工具证据".repeat(4000));
        state.withObject("/observations").set("diagnose:4:0", evidence);
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);

        fixture.step();

        var run = fixture.store.get(id);
        JsonNode intent = run.state().path("modelIntent");
        assertEquals("QUEUED", run.status());
        assertTrue(intent.isObject());
        assertTrue(
                AgentContext.inputUpperBound(intent) + intent.path("maxOutputTokens").asInt()
                        <= 19018);
        assertEquals(originalMessages, run.state().path("messages"));
        assertEquals(evidence, run.state().path("observations").path("diagnose:4:0"));
        assertEquals(0, fixture.clients.modelRequests.size());
    }

    @Test
    void noFeasibleContextStopsBeforeRegisteringOrSendingANewModelIntent() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("no-context-budget", -1);
        fixture.step();
        ObjectNode state = fixture.store.get(id).state();
        state.put("tokens", 31500);
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);

        fixture.step();

        var run = fixture.store.get(id);
        assertEquals("BUDGET_EXCEEDED", run.status());
        assertFalse(run.state().has("modelIntent"));
        assertEquals(31500, run.state().path("tokens").asInt());
        assertEquals(0, fixture.clients.modelRequests.size());
    }

    @Test
    void alreadyRegisteredModelIntentIsReplayedExactlyAndNeverRecompressed() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("existing-context-intent", -1);
        fixture.step();
        ObjectNode state = fixture.store.get(id).state();
        ObjectNode registered = request(history(4), 19018);
        registered
                .put("callId", "already-registered-call")
                .put("provider", "DEEPSEEK")
                .put("model", "test-model");
        state.set("modelIntent", registered.deepCopy());
        state.put("turns", 4).put("tokens", 12982);
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);
        fixture.clients.model =
                ignored -> AgentJson.read("{\"outcome\":\"UNKNOWN\",\"usageKnown\":false}");

        fixture.step();

        assertEquals(registered, fixture.clients.modelRequests.get(0));
        assertEquals(registered, fixture.store.get(id).state().path("modelIntent"));
        assertEquals("NEEDS_ATTENTION", fixture.store.get(id).status());
    }

    @Test
    void eightToolDecisionRoundsWithLargeChineseEvidenceFinishInsideEveryNativeBudget() {
        var fixture = new AgentTestSupport();
        String rawDescription = "中文工单完整现场诊断".repeat(1200) + "原文结尾";
        ObjectNode knowledge = AgentJson.object();
        StringBuilder text = new StringBuilder();
        for (int index = 1; index <= 4; index++) {
            knowledge
                    .withArray("/citations")
                    .add(
                            AgentJson.object()
                                    .put("sourceId", "S" + index)
                                    .put("documentId", index)
                                    .put("chunkId", index)
                                    .put("documentName", "处置手册" + index));
            text.append("[S")
                    .append(index)
                    .append("]\n文档：处置手册\n正文：")
                    .append("这是完整知识证据，不应全部重复发送模型。".repeat(180))
                    .append("\n\n");
        }
        knowledge.put("evidence", text.toString());
        var clients =
                new AgentTestSupport.FakeClients() {
                    @Override
                    JsonNode call(
                            String audience,
                            String path,
                            String method,
                            JsonNode body,
                            Context actor) {
                        if (path.equals("/internal/rag/search")) return knowledge.deepCopy();
                        JsonNode result = super.call(audience, path, method, body, actor);
                        if (path.equals("/internal/agent/tickets/7")) {
                            ((ObjectNode) result).put("description", rawDescription);
                        }
                        return result;
                    }
                };
        clients.model =
                request -> {
                    assertTrue(AgentContext.inputUpperBound(request) <= 32768);
                    assertTrue(
                            AgentContext.inputUpperBound(request)
                                            + request.path("maxOutputTokens").asInt()
                                    <= request.path("remainingTokens").asInt());
                    int round = clients.modelRequests.size();
                    if (round > 8)
                        return ((ObjectNode) AgentTestSupport.finalResponse())
                                .put("totalTokens", 1900);
                    ObjectNode response =
                            AgentJson.object()
                                    .put("outcome", "TOOL_CALLS")
                                    .put("usageKnown", true)
                                    .put("totalTokens", 1900);
                    ObjectNode assistant =
                            AgentJson.object().put("role", "assistant").put("content", "结合工单和知识检查");
                    String[] nextTools =
                            round <= 6
                                    ? new String[] {"ticket_get", "knowledge_search"}
                                    : new String[] {"ticket_get"};
                    for (String name : nextTools) {
                        ObjectNode call =
                                AgentJson.object()
                                        .put("type", "function")
                                        .put("id", "round-" + round + "-" + name);
                        call.set(
                                "function",
                                AgentJson.object()
                                        .put("name", name)
                                        .put(
                                                "arguments",
                                                name.equals("ticket_get")
                                                        ? "{}"
                                                        : "{\"query\":\"故障诊断证据\"}"));
                        assistant.withArray("/tool_calls").add(call);
                    }
                    response.set("assistantMessage", assistant);
                    return response;
                };
        AgentRuntime runtime =
                new AgentRuntime(fixture.store, clients, new AgentTools(clients), true);
        String id = fixture.create("eight-context-rounds", -1);
        for (int step = 0; step < 140 && fixture.store.get(id).status().equals("QUEUED"); step++) {
            fixture.jdbc.update("UPDATE agent_run SET next_attempt=TIMESTAMPADD(SECOND,-1,NOW(3))");
            runtime.tick();
        }

        var run = fixture.store.get(id);
        assertEquals(
                "COMPLETED",
                run.status(),
                () ->
                        "turns="
                                + run.state().path("turns")
                                + ", tools="
                                + run.state().path("toolCount")
                                + ", tokens="
                                + run.state().path("tokens")
                                + ", reason="
                                + run.state().path("message"));
        assertTrue(run.state().path("ticketResolved").asBoolean());
        assertEquals(9, clients.modelRequests.size());
        assertEquals(17100, run.state().path("tokens").asInt());
        assertEquals(
                rawDescription,
                run.state().path("observations").path("diagnose:1:0").path("description").asText());
        assertEquals(knowledge, run.state().path("observations").path("diagnose:1:1"));
        assertTrue(
                fixture.store.events(id, 0).stream()
                        .anyMatch(
                                event ->
                                        event.path("type").asText().equals("TOOL_OBSERVATION")
                                                && event.path("payload")
                                                        .path("result")
                                                        .equals(knowledge)));
    }

    private static ObjectNode request(ArrayNode messages, int remaining) {
        ObjectNode request =
                AgentJson.object().put("maxOutputTokens", 1800).put("remainingTokens", remaining);
        request.set("messages", messages);
        request.set("tools", AgentTools.schemas());
        return request;
    }

    private static ArrayNode history(int rounds) {
        ArrayNode messages = AgentJson.MAPPER.createArrayNode();
        messages.add(AgentJson.object().put("role", "system").put("content", "依据证据诊断，遵守权限与精确审批。"));
        messages.add(AgentJson.object().put("role", "user").put("content", "诊断当前工单"));
        for (int round = 0; round < rounds; round++) {
            ObjectNode assistant =
                    AgentJson.object().put("role", "assistant").put("content", "读取当前事实");
            ArrayNode calls = assistant.putArray("tool_calls");
            for (int index = 0; index < 2; index++) {
                ObjectNode call =
                        AgentJson.object()
                                .put("id", "call-" + round + "-" + index)
                                .put("type", "function");
                call.set(
                        "function",
                        AgentJson.object().put("name", "ticket_get").put("arguments", "{}"));
                calls.add(call);
            }
            messages.add(assistant);
            for (JsonNode call : calls)
                messages.add(
                        AgentJson.object()
                                .put("role", "tool")
                                .put("tool_call_id", call.path("id").asText())
                                .put(
                                        "content",
                                        "完整证据见observations/"
                                                + call.path("id").asText()
                                                + "\n"
                                                + "中文证据".repeat(450)));
        }
        return messages;
    }
}
