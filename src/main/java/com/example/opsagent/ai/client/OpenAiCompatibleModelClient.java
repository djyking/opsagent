package com.example.opsagent.ai.client;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.example.opsagent.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 通过 OpenAI 兼容的 Chat Completions API 调用可配置大模型。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Component
@ConditionalOnProperty(prefix = "ops-agent.ai", name = "enabled", havingValue = "true")
public class OpenAiCompatibleModelClient implements AiModelClient {

    private final AiProperties properties;

    private final RestClient restClient;

    private final URI chatCompletionsEndpoint;

    public OpenAiCompatibleModelClient(AiProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.chatCompletionsEndpoint = endpoint(properties.getBaseUrl());
    }

    @Override
    public AiModelResponse chat(String systemPrompt, String userPrompt) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("OPS_AGENT_AI_API_KEY 未配置");
        }
        Map<String, Object> request = Map.of(
            "model", properties.getModel(),
            "temperature", 0.1,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            )
        );
        JsonNode response = restClient.post()
            .uri(chatCompletionsEndpoint)
            .header("Authorization", "Bearer " + properties.getApiKey())
            .body(request)
            .retrieve()
            .body(JsonNode.class);
        if (response == null || !response.path("choices").isArray() || response.path("choices").isEmpty()) {
            throw new IllegalStateException("模型返回结构不合法");
        }
        String answer = response.path("choices").path(0).path("message").path("content").asText();
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("模型未返回有效答案");
        }
        String modelName = response.path("model").asText(properties.getModel());
        JsonNode usage = response.path("usage");
        return new AiModelResponse(answer, modelName, nullableInt(usage, "prompt_tokens"),
            nullableInt(usage, "completion_tokens"));
    }

    private Integer nullableInt(JsonNode node, String field) {
        return node.has(field) && node.get(field).canConvertToInt() ? node.get(field).intValue() : null;
    }

    private URI endpoint(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("AI base-url 未配置");
        }
        URI endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
            || !StringUtils.hasText(endpoint.getHost())) {
            throw new IllegalStateException("AI base-url 必须是有效的 HTTP(S) 地址");
        }
        return endpoint;
    }
}
