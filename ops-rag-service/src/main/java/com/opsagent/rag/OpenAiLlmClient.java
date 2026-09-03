package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 使用 OpenAI Responses API 调用 GPT 模型并解析统一用量。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Component
public class OpenAiLlmClient implements LlmClient {
    private final AiProperties properties;
    private final AiHttpExecutor http;
    private final AiStreamHttpExecutor streamHttp;

    OpenAiLlmClient(
            AiProperties properties,
            AiHttpExecutor http,
            AiStreamHttpExecutor streamHttp) {
        this.properties = properties;
        this.http = http;
        this.streamHttp = streamHttp;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public boolean configured() {
        return properties.isEnabled() && settings().configured();
    }

    @Override
    public String model() {
        return settings().getModel();
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        requireConfigured();
        Map<String, Object> body = Map.of(
                "model", model(),
                "input", List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt())),
                "max_output_tokens", request.maxOutputTokens(),
                "reasoning", Map.of("effort", "low"));
        JsonNode response = http.post(
                provider(),
                settings().getBaseUrl(),
                "/responses",
                settings().getApiKey(),
                body,
                properties.getTimeoutSeconds(),
                properties.getMaximumAttempts());
        String text = response.path("output_text").asText();
        if (text.isBlank()) {
            text = response.path("output").findValues("text").stream()
                    .map(JsonNode::asText)
                    .filter(value -> !value.isBlank())
                    .reduce("", (left, right) -> left + right);
        }
        if (text.isBlank()) {
            throw new AiProviderException(provider(), 502, "AI 服务返回了空回答。", null);
        }
        JsonNode usage = response.path("usage");
        return new LlmResult(
                text.trim(),
                provider(),
                model(),
                usage.path("input_tokens").asInt(),
                usage.path("output_tokens").asInt());
    }

    @Override
    public LlmResult stream(LlmRequest request, Consumer<String> onDelta) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("input", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())));
        body.put("max_output_tokens", request.maxOutputTokens());
        body.put("reasoning", Map.of("effort", "low"));
        body.put("stream", true);
        StringBuilder answer = new StringBuilder();
        AtomicInteger inputTokens = new AtomicInteger();
        AtomicInteger outputTokens = new AtomicInteger();
        streamHttp.post(
                provider(),
                settings().getBaseUrl(),
                "/responses",
                settings().getApiKey(),
                body,
                properties.getTimeoutSeconds(),
                properties.getMaximumAttempts(),
                event -> handleEvent(event, answer, inputTokens, outputTokens, onDelta));
        if (answer.isEmpty()) {
            throw new AiProviderException(provider(), 502, "AI 服务返回了空回答。", null);
        }
        return new LlmResult(
                answer.toString().trim(),
                provider(),
                model(),
                inputTokens.get(),
                outputTokens.get());
    }

    private boolean handleEvent(
            JsonNode event,
            StringBuilder answer,
            AtomicInteger inputTokens,
            AtomicInteger outputTokens,
            Consumer<String> onDelta) {
        String type = event.path("type").asText();
        if ("response.output_text.delta".equals(type)) {
            JsonNode deltaNode = event.path("delta");
            if (!deltaNode.isTextual()) {
                return false;
            }
            String delta = deltaNode.textValue();
            if (!delta.isEmpty()) {
                answer.append(delta);
                onDelta.accept(delta);
                return true;
            }
        } else if ("response.completed".equals(type)) {
            JsonNode usage = event.path("response").path("usage");
            inputTokens.set(usage.path("input_tokens").asInt());
            outputTokens.set(usage.path("output_tokens").asInt());
        }
        return false;
    }

    private AiProperties.ProviderSettings settings() {
        return properties.settings(provider());
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new AiProviderException(provider(), 0, "当前 AI 服务未完成配置，请联系管理员。", null);
        }
    }
}
