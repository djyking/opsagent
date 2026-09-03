package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 为兼容 Chat Completions 协议的供应商提供公共请求和响应解析。
 *
 * @author heyu
 * @since 2026/8/31
 */
public abstract class ChatCompletionsLlmClient implements LlmClient {
    private final AiProperties properties;
    private final AiHttpExecutor http;

    ChatCompletionsLlmClient(AiProperties properties, AiHttpExecutor http) {
        this.properties = properties;
        this.http = http;
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
        if (!configured()) {
            throw new AiProviderException(provider(), 0, "当前 AI 服务未完成配置，请联系管理员。", null);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())));
        body.put("max_tokens", request.maxOutputTokens());
        body.putAll(additionalBody());
        JsonNode response = http.post(
                provider(),
                settings().getBaseUrl(),
                "/chat/completions",
                settings().getApiKey(),
                body,
                properties.getTimeoutSeconds(),
                properties.getMaximumAttempts());
        String text = response.path("choices").path(0).path("message").path("content").asText();
        if (text.isBlank()) {
            throw new AiProviderException(provider(), 502, "AI 服务返回了空回答。", null);
        }
        JsonNode usage = response.path("usage");
        return new LlmResult(
                text.trim(),
                provider(),
                model(),
                usage.path("prompt_tokens").asInt(),
                usage.path("completion_tokens").asInt());
    }

    private AiProperties.ProviderSettings settings() {
        return properties.settings(provider());
    }

    protected Map<String, Object> additionalBody() {
        return Map.of();
    }
}
