package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 HTTP/SSE 边界验证截断、续写、用量和协议完整性。
 *
 * @author heyu
 * @since 2026/9/3
 */
class LlmCompletionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private AiProperties properties;
    private DeepSeekLlmClient chat;
    private OpenAiLlmClient openai;

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setMaximumAttempts(1);
        AiProperties.ProviderSettings settings = new AiProperties.ProviderSettings();
        settings.setApiKey("unit-test-key");
        settings.setModel("unit-test-model");
        settings.setBaseUrl(server.url("/").toString());
        properties.setProviders(Map.of("deepseek", settings, "openai", settings));
        AiHttpExecutor http = new AiHttpExecutor();
        AiStreamHttpExecutor stream = new AiStreamHttpExecutor(mapper);
        chat = new DeepSeekLlmClient(properties, http, stream);
        openai = new OpenAiLlmClient(properties, http, stream);
    }

    @AfterEach
    void shutdown() throws IOException { server.shutdown(); }

    @Test
    void shouldContinueLengthLimitedAnswerAndAccumulateUsage() throws Exception {
        server.enqueue(json("第一步，", "length", 10, 4));
        server.enqueue(json("检查连接。", "stop", 20, 5));

        LlmResult result = chat.generate(request());

        assertThat(result.text()).isEqualTo("第一步，检查连接。");
        assertThat(result.generationComplete()).isTrue();
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.continuationCount()).isEqualTo(1);
        assertThat(result.inputTokens()).isEqualTo(30);
        assertThat(result.outputTokens()).isEqualTo(9);
        JsonNode initial = mapper.readTree(server.takeRequest().getBody().readUtf8());
        JsonNode continued = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(initial.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(continued.path("messages").path(2).path("content").asText()).isEqualTo("第一步，");
        assertThat(continued.path("messages").path(3).path("content").asText()).contains("不要重复已有内容");
    }

    @Test
    void shouldStopAtBoundedContinuationLimitAndMarkIncomplete() {
        server.enqueue(json("一", "length", 10, 5));
        server.enqueue(json("二", "length", 20, 5));
        server.enqueue(json("三", "length", 30, 5));

        LlmResult result = chat.generate(request());

        assertThat(result.text()).isEqualTo("一二三");
        assertThat(result.generationComplete()).isFalse();
        assertThat(result.finishReason()).isEqualTo("length");
        assertThat(result.continuationCount()).isEqualTo(2);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void shouldStreamContinuationWithoutDroppingEarlierTokens() {
        server.enqueue(sse(delta("检查") + finish("length") + "data: [DONE]\n\n"));
        server.enqueue(sse(delta("连接。") + finish("stop") + "data: [DONE]\n\n"));
        StringBuilder displayed = new StringBuilder();

        LlmResult result = chat.stream(request(), displayed::append);

        assertThat(displayed.toString()).isEqualTo("检查连接。");
        assertThat(result.text()).isEqualTo(displayed.toString());
        assertThat(result.generationComplete()).isTrue();
        assertThat(result.continuationCount()).isEqualTo(1);
    }

    @Test
    void shouldRequireBothChatFinishReasonAndDoneMarker() {
        server.enqueue(sse(delta("半句话") + finish("stop")));
        LlmResult missingDone = chat.stream(request(), delta -> {});
        assertThat(missingDone.generationComplete()).isFalse();
        assertThat(missingDone.finishReason()).isEqualTo("stream_interrupted");

        server.enqueue(sse(delta("半句话") + "data: [DONE]\n\n"));
        LlmResult missingReason = chat.stream(request(), delta -> {});
        assertThat(missingReason.generationComplete()).isFalse();
        assertThat(missingReason.finishReason()).isEqualTo("stream_interrupted");
    }

    @Test
    void shouldKeepPartialTextOnMalformedSseAndNeverReplayIt() {
        properties.setMaximumAttempts(3);
        server.enqueue(sse(delta("保留这一段") + "data: {broken-json}\n\n"));

        LlmResult result = chat.stream(request(), delta -> {});

        assertThat(result.text()).isEqualTo("保留这一段");
        assertThat(result.generationComplete()).isFalse();
        assertThat(result.finishReason()).isEqualTo("stream_interrupted");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldPreserveEarlierAnswerWhenContinuationProviderFails() {
        server.enqueue(json("已生成内容", "length", 10, 5));
        server.enqueue(new MockResponse().setResponseCode(503));

        LlmResult result = chat.generate(request());

        assertThat(result.text()).isEqualTo("已生成内容");
        assertThat(result.generationComplete()).isFalse();
        assertThat(result.finishReason()).isEqualTo("continuation_failed");
    }

    @Test
    void shouldNotContinueContentFilteredOrUnknownTermination() {
        server.enqueue(json("部分内容", "content_filter", 10, 5));
        LlmResult result = chat.generate(request());
        assertThat(result.generationComplete()).isFalse();
        assertThat(result.finishReason()).isEqualTo("content_filter");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldNotClaimCompleteWhenContinuationIsEmpty() {
        server.enqueue(json("未结束的句子", "length", 10, 5));
        server.enqueue(json("", "stop", 20, 0));
        LlmResult result = chat.generate(request());
        assertThat(result.generationComplete()).isFalse();
        assertThat(result.finishReason()).isEqualTo("empty_continuation");
    }

    @Test
    void shouldAcceptResponsesCompletedEventWithoutChatDoneMarker() {
        server.enqueue(sse(openDelta("完整回答。") + openFinish("completed", null)));
        LlmResult result = openai.stream(request(), delta -> {});
        assertThat(result.generationComplete()).isTrue();
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.outputTokens()).isEqualTo(6);
    }

    @Test
    void shouldContinueIncompleteResponsesAndRequireItsTerminalEvent() {
        server.enqueue(sse(openDelta("第一段，") + openFinish("incomplete", "max_output_tokens")));
        server.enqueue(sse(openDelta("完整收尾。") + openFinish("completed", null)));
        LlmResult continued = openai.stream(request(), delta -> {});
        assertThat(continued.text()).isEqualTo("第一段，完整收尾。");
        assertThat(continued.generationComplete()).isTrue();
        assertThat(continued.continuationCount()).isEqualTo(1);

        server.enqueue(sse(openDelta("未完成")));
        LlmResult missingTerminal = openai.stream(request(), delta -> {});
        assertThat(missingTerminal.generationComplete()).isFalse();
        assertThat(missingTerminal.finishReason()).isEqualTo("stream_interrupted");
    }

    @Test
    void shouldExposeResponsesSynchronousIncompleteStatus() {
        properties.setMaximumContinuations(0);
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"status\":\"incomplete\",\"output_text\":\"未结束\","
                        + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}"));
        LlmResult result = openai.generate(request());
        assertThat(result.generationComplete()).isFalse();
        assertThat(result.finishReason()).isEqualTo("length");
    }

    private LlmRequest request() { return new LlmRequest("system", "user", properties.getMaxOutputTokens()); }

    private MockResponse json(String content, String reason, int input, int output) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "choices", java.util.List.of(Map.of(
                            "message", Map.of("content", content), "finish_reason", reason)),
                    "usage", Map.of("prompt_tokens", input, "completion_tokens", output)));
            return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MockResponse sse(String content) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(content);
    }

    private String delta(String value) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + value + "\"}}]}\n\n";
    }

    private String finish(String value) {
        return "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"" + value + "\"}]}\n\n";
    }

    private String openDelta(String value) {
        return "data: {\"type\":\"response.output_text.delta\",\"delta\":\"" + value + "\"}\n\n";
    }

    private String openFinish(String status, String reason) {
        return "data: {\"type\":\"response." + status + "\",\"response\":{\"status\":\""
                + status + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":6}"
                + (reason == null ? "" : ",\"incomplete_details\":{\"reason\":\"" + reason + "\"}")
                + "}}\n\n";
    }
}

