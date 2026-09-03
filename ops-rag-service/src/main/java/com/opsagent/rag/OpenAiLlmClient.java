package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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

    OpenAiLlmClient(AiProperties properties, AiHttpExecutor http) {
        this.properties = properties;
        this.http = http;
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

    private AiProperties.ProviderSettings settings() {
        return properties.settings(provider());
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new AiProviderException(provider(), 0, "当前 AI 服务未完成配置，请联系管理员。", null);
        }
    }
}
