package com.opsagent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 读取 AI 供应商的 SSE 响应，并在首个 Token 前执行有限重试。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class AiStreamHttpExecutor {
    private final ObjectMapper mapper;

    AiStreamHttpExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void post(
            String provider,
            String baseUrl,
            String path,
            String apiKey,
            Map<String, Object> body,
            int timeoutSeconds,
            int maximumAttempts,
            StreamEventHandler handler) {
        int attempts = Math.max(1, Math.min(maximumAttempts, 3));
        for (int attempt = 1; attempt <= attempts; attempt++) {
            AtomicBoolean emitted = new AtomicBoolean();
            try {
                streamOnce(
                        provider,
                        baseUrl,
                        path,
                        apiKey,
                        body,
                        timeoutSeconds,
                        handler,
                        emitted);
                return;
            } catch (AiProviderException exception) {
                boolean retryable = exception.statusCode() == 429
                        || exception.statusCode() >= 500
                        || exception.statusCode() == 0;
                if (emitted.get() || !retryable || attempt == attempts) {
                    throw exception;
                }
                pause(provider, attempt);
            } catch (ResourceAccessException exception) {
                if (emitted.get() || attempt == attempts) {
                    throw new AiProviderException(
                            provider, 0, "AI 服务响应超时，请稍后重试。", exception);
                }
                pause(provider, attempt);
            }
        }
    }

    private void streamOnce(
            String provider,
            String baseUrl,
            String path,
            String apiKey,
            Map<String, Object> body,
            int timeoutSeconds,
            StreamEventHandler handler,
            AtomicBoolean emitted) {
        RestClient client = client(timeoutSeconds);
        client.post()
                .uri(normalize(baseUrl) + path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> headers.setBearerAuth(apiKey))
                .body(body)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status < 200 || status >= 300) {
                        throw failure(provider, status);
                    }
                    readEvents(provider, response.getBody(), handler, emitted);
                    return null;
                });
    }

    private void readEvents(
            String provider,
            java.io.InputStream input,
            StreamEventHandler handler,
            AtomicBoolean emitted) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    dispatch(provider, data, handler, emitted);
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
            dispatch(provider, data, handler, emitted);
        } catch (IOException exception) {
            throw new AiProviderException(provider, 0, "AI 流式响应读取失败。", exception);
        }
    }

    private void dispatch(
            String provider,
            StringBuilder data,
            StreamEventHandler handler,
            AtomicBoolean emitted) {
        if (data.isEmpty() || "[DONE]".contentEquals(data)) {
            return;
        }
        try {
            JsonNode event = mapper.readTree(data.toString());
            if (handler.handle(event)) {
                emitted.set(true);
            }
        } catch (JsonProcessingException exception) {
            throw new AiProviderException(provider, 502, "AI 服务返回了无效的流式事件。", exception);
        }
    }

    private RestClient client(int timeoutSeconds) {
        int safeTimeout = Math.max(3, Math.min(timeoutSeconds, 120));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.min(safeTimeout, 15)));
        factory.setReadTimeout(Duration.ofSeconds(safeTimeout));
        return RestClient.builder().requestFactory(factory).build();
    }

    private AiProviderException failure(String provider, int status) {
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
        return new AiProviderException(provider, status, message, null);
    }

    private String normalize(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private void pause(String provider, int attempt) {
        try {
            Thread.sleep(250L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException(provider, 0, "AI 请求已取消。", exception);
        }
    }

    /**
     * 处理一个供应商 SSE JSON 事件，并返回该事件是否产生了用户可见文本。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @FunctionalInterface
    interface StreamEventHandler {
        boolean handle(JsonNode event);
    }
}
