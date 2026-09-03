package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * 执行带超时和有限重试的 AI HTTP 请求，且不记录密钥或供应商原始响应体。
 *
 * @author heyu
 * @since 2026/8/30
 */
@Component
public class AiHttpExecutor {
    JsonNode post(
            String provider,
            String baseUrl,
            String path,
            String apiKey,
            Map<String, Object> body,
            int timeoutSeconds,
            int maximumAttempts) {
        RestClient client = client(timeoutSeconds);
        int attempts = Math.max(1, Math.min(maximumAttempts, 3));
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return client.post()
                        .uri(normalize(baseUrl) + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(headers -> headers.setBearerAuth(apiKey))
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt == attempts) {
                    throw failure(provider, status, exception);
                }
                pause(attempt);
            } catch (ResourceAccessException exception) {
                if (attempt == attempts) {
                    throw new AiProviderException(provider, 0, "AI 服务响应超时，请稍后重试。", exception);
                }
                pause(attempt);
            }
        }
        throw new AiProviderException(provider, 0, "AI 服务暂时不可用。", null);
    }

    private RestClient client(int timeoutSeconds) {
        int safeTimeout = Math.max(3, Math.min(timeoutSeconds, 120));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.min(safeTimeout, 15)));
        factory.setReadTimeout(Duration.ofSeconds(safeTimeout));
        return RestClient.builder().requestFactory(factory).build();
    }

    private AiProviderException failure(
            String provider, int status, RestClientResponseException exception) {
        String message;
        if (status == 401 || status == 403) {
            message = "当前 AI 服务鉴权失败，请联系管理员。";
        } else if (status == 429) {
            message = "AI 服务当前请求较多或额度不足，请稍后重试。";
        } else if (status >= 500) {
            message = "AI 供应商服务暂时不可用，请稍后重试。";
        } else {
            message = "AI 服务请求不被供应商接受，请联系管理员检查模型配置。";
        }
        return new AiProviderException(provider, status, message, exception);
    }

    private String normalize(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(250L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("unknown", 0, "AI 请求已取消。", exception);
        }
    }
}
