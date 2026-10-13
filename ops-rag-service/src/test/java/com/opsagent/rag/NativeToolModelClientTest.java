package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在真实 HTTP 边界验证纯工具响应、调用回放、两种原生协议及预算/截断拒绝。
 *
 * @author heyu
 * @since 2026/9/3
 */
class NativeToolModelClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private AiProperties properties;
    private NativeToolModelClient client;

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
        properties = configured(server);
        client = new NativeToolModelClient(properties, new AiHttpExecutor(), mapper);
    }

    @AfterEach
    void close() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldAcceptNativeToolOnlyTurnAndReplayExactCallIdWithObservation() throws Exception {
        server.enqueue(toolResponse("tool_calls", "{\"ticketId\":7}"));
        var first = client.call(request("turn-1", "deepseek", messages(), tools()), null);
        assertThat(first.outcome()).isEqualTo("TOOL_CALLS");
        assertThat(first.assistantMessage().get("content")).isNull();
        assertThat(first.inputTokens()).isEqualTo(10);
        assertThat(first.outputTokens()).isEqualTo(4);
        assertThat(first.totalTokens()).isEqualTo(14);
        assertThat(first.usageKnown()).isTrue();
        var sent = server.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/chat/completions");
        var body = mapper.readTree(sent.getBody().readUtf8());
        assertThat(body.path("tools").path(0).path("function").path("name").asText())
                .isEqualTo("ticket_get");
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");

        List<Map<String, Object>> history = new ArrayList<>(messages());
        history.add(first.assistantMessage());
        history.add(
                Map.of(
                        "role",
                        "tool",
                        "tool_call_id",
                        "call_1",
                        "content",
                        "{\"status\":\"OPEN\"}"));
        server.enqueue(
                json(
                        """
                        {"model":"model-test","choices":[{"message":{"role":"assistant","content":"需要进一步排查"},
                          "finish_reason":"stop"}]}
                        """));
        var second = client.call(request("turn-2", "deepseek", history, tools()), null);
        assertThat(second.outcome()).isEqualTo("FINAL");
        assertThat(second.usageKnown()).isFalse();
        assertThat(second.totalTokens()).isNull();
        var replay = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(replay.path("messages").path(1).path("tool_calls").path(0).path("id").asText())
                .isEqualTo("call_1");
        assertThat(replay.path("messages").path(2).path("tool_call_id").asText())
                .isEqualTo("call_1");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void shouldMapResponsesFunctionsAndToolOutputsWithoutPersistingReasoning() throws Exception {
        properties.getProviders().get("openai").setApiStyle("responses");
        server.enqueue(
                json(
                        """
                        {"model":"model-test","status":"completed","output":[{"type":"function_call",
                         "call_id":"call_1","name":"ticket_get","arguments":"{\\"ticketId\\":7}"}],
                         "usage":{"input_tokens":11,"output_tokens":4,"total_tokens":15}}
                        """));
        var first = client.call(request("turn-o1", "openai", messages(), tools()), "ticket_get");
        assertThat(first.outcome()).isEqualTo("TOOL_CALLS");
        var sent = server.takeRequest();
        assertThat(sent.getPath()).isEqualTo("/responses");
        var body = mapper.readTree(sent.getBody().readUtf8());
        assertThat(body.path("tools").path(0).path("name").asText()).isEqualTo("ticket_get");
        assertThat(body.path("tool_choice").path("name").asText()).isEqualTo("ticket_get");
        assertThat(body.path("store").asBoolean()).isFalse();

        List<Map<String, Object>> history = new ArrayList<>(messages());
        history.add(first.assistantMessage());
        history.add(
                Map.of(
                        "role",
                        "tool",
                        "tool_call_id",
                        "call_1",
                        "content",
                        "authorized observation"));
        server.enqueue(
                json(
                        """
                        {"status":"completed","output":[{"type":"message","content":[
                         {"type":"output_text","text":"完成"}]}]}
                        """));
        assertThat(client.call(request("turn-o2", "openai", history, tools()), null).outcome())
                .isEqualTo("FINAL");
        var followup = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(followup.path("input").path(1).path("type").asText()).isEqualTo("function_call");
        assertThat(followup.path("input").path(2).path("type").asText())
                .isEqualTo("function_call_output");
        assertThat(followup.path("input").path(2).path("call_id").asText()).isEqualTo("call_1");
    }

    @Test
    void shouldNeverExecuteTruncatedOrMalformedToolArgumentsOrParseTextAsCalls() {
        server.enqueue(toolResponse("length", "{\"ticketId\":"));
        var truncated = client.call(request("cut", "deepseek", messages(), tools()), null);
        assertThat(truncated.outcome()).isEqualTo("INCOMPLETE");
        assertThat(truncated.assistantMessage()).doesNotContainKey("tool_calls");
        server.enqueue(toolResponse("tool_calls", "{\"ticketId\":7} trailing"));
        var invalid = client.call(request("bad", "deepseek", messages(), tools()), null);
        assertThat(invalid.outcome()).isEqualTo("INCOMPLETE");
        assertThat(invalid.finishReason()).isEqualTo("invalid_tool_calls");
        server.enqueue(
                json(
                        """
                        {"choices":[{"message":{"content":"<tool>ticket_get(7)</tool>"},"finish_reason":"stop"}]}
                        """));
        var text = client.call(request("text", "deepseek", messages(), tools()), null);
        assertThat(text.outcome()).isEqualTo("FINAL");
        assertThat(text.assistantMessage()).doesNotContainKey("tool_calls");
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void shouldAllowTextOnlyNodesAndRejectExceededBudgetBeforeHttp() throws Exception {
        server.enqueue(
                json(
                        """
                        {"choices":[{"message":{"content":"诊断摘要"},"finish_reason":"stop"}]}
                        """));
        assertThat(
                        client.call(request("summary", "deepseek", messages(), List.of()), null)
                                .outcome())
                .isEqualTo("FINAL");
        var body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(body.has("tools")).isFalse();
        assertThat(body.has("tool_choice")).isFalse();
        var insufficient =
                new InternalAgentDtos.TurnRequest(
                        "over", "deepseek", "model-test", messages(), tools(), 400, 10);
        assertThatThrownBy(() -> client.call(insufficient, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("MODEL_TOKEN_BUDGET_EXCEEDED");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void deadlineIncludesTheResponseBodyAndDoesNotRetryInsideHttpAdapter() {
        server.enqueue(toolResponse("tool_calls", "{\"ticketId\":7}").setBodyDelay(2, TimeUnit.SECONDS));
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.call(request("body-timeout", "deepseek", messages(), tools()),
                null, Duration.ofMillis(300)))
                .isInstanceOfSatisfying(AiProviderException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(AiProviderException.FailureKind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void oversizedBodyIsCancelledBeforeFullBufferingAndClassifiedAsProtocolFailure() {
        server.enqueue(new MockResponse().setBody("x".repeat(600_000)));
        assertThatThrownBy(() -> client.call(request("body-limit", "deepseek", messages(), tools()), null))
                .isInstanceOfSatisfying(AiProviderException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(AiProviderException.FailureKind.PROTOCOL));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    static AiProperties configured(MockWebServer server) {
        var properties = new AiProperties();
        properties.setEnabled(true);
        properties.setMaximumAttempts(3);
        properties.setMaxOutputTokens(4096);
        var deepseek = new AiProperties.ProviderSettings();
        deepseek.setApiKey("test-key-not-production");
        deepseek.setModel("model-test");
        deepseek.setBaseUrl(server.url("/").toString());
        var openai = new AiProperties.ProviderSettings();
        openai.setApiKey("test-key-not-production");
        openai.setModel("model-test");
        openai.setBaseUrl(server.url("/").toString());
        properties.setProviders(Map.of("deepseek", deepseek, "openai", openai));
        return properties;
    }

    static InternalAgentDtos.TurnRequest request(
            String id,
            String provider,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {
        return new InternalAgentDtos.TurnRequest(
                id, provider, "model-test", messages, tools, 400, 12000);
    }

    static List<Map<String, Object>> messages() {
        return List.of(Map.of("role", "user", "content", "请查询工单7"));
    }

    static List<Map<String, Object>> tools() {
        return List.of(
                Map.of(
                        "type",
                        "function",
                        "function",
                        Map.of(
                                "name",
                                "ticket_get",
                                "description",
                                "Read an authorized ticket",
                                "parameters",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("ticketId", Map.of("type", "integer")),
                                        "required",
                                        List.of("ticketId"),
                                        "additionalProperties",
                                        false))));
    }

    static MockResponse toolResponse(String finish, String arguments) {
        try {
            var mapper = new ObjectMapper();
            return json(
                    mapper.writeValueAsString(
                            Map.of(
                                    "model",
                                    "model-test",
                                    "choices",
                                    List.of(
                                            Map.of(
                                                    "message",
                                                    Map.of(
                                                            "role",
                                                            "assistant",
                                                            "tool_calls",
                                                            List.of(
                                                                    Map.of(
                                                                            "id",
                                                                            "call_1",
                                                                            "type",
                                                                            "function",
                                                                            "function",
                                                                            Map.of(
                                                                                    "name",
                                                                                    "ticket_get",
                                                                                    "arguments",
                                                                                    arguments)))),
                                                    "finish_reason",
                                                    finish)),
                                    "usage",
                                    Map.of("prompt_tokens", 10, "completion_tokens", 4))));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static MockResponse json(String value) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(value);
    }
}
