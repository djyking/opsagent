package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Map;

/**
 * 执行带超时和有限重试的 AI HTTP 请求，且不记录密钥或供应商原始响应体。
 *
 * @author heyu
 * @since 2026/8/30
 */
@Component
public class AiHttpExecutor {
    private final HttpClient boundedClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper boundedJson = new ObjectMapper();

    /** Includes connection, headers and the complete response body in one deadline; performs no retry. */
    JsonNode postBounded(String provider, String baseUrl, String path, String apiKey,
            Map<String, Object> body, Duration timeout) {
        String payload;
        try { payload = boundedJson.writeValueAsString(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AiProviderException(provider, 0, "模型请求格式无效", null,
                    AiProviderException.FailureKind.PROTOCOL);
        }
        long millis = Math.max(1, Math.min(timeout.toMillis(), 55_000));
        var request = HttpRequest.newBuilder(URI.create(normalize(baseUrl) + path))
                .timeout(Duration.ofMillis(millis)).header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        var pending = boundedClient.sendAsync(request, response -> new LimitedBodySubscriber());
        try {
            var response = pending.get(millis, TimeUnit.MILLISECONDS);
            if (response.statusCode() / 100 != 2) {
                throw new AiProviderException(provider, response.statusCode(), "模型供应商拒绝或暂不可用", null,
                        AiProviderException.FailureKind.HTTP);
            }
            try { return boundedJson.readTree(response.body()); }
            catch (java.io.IOException exception) {
                throw new AiProviderException(provider, 0, "模型响应不是有效JSON", null,
                        AiProviderException.FailureKind.PROTOCOL);
            }
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new AiProviderException(provider, 0, "模型响应超过本次时间限额", null,
                    AiProviderException.FailureKind.TIMEOUT);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new AiProviderException(provider, 0, "模型请求已中断", null,
                    AiProviderException.FailureKind.CANCELLED);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
            if (cause instanceof ResponseTooLarge) {
                throw new AiProviderException(provider, 0, "模型响应超出大小限制", null,
                        AiProviderException.FailureKind.PROTOCOL);
            }
            boolean timedOut = cause instanceof java.net.http.HttpTimeoutException;
            throw new AiProviderException(provider, 0, timedOut ? "模型连接或响应超时" : "模型网络连接失败", null,
                    timedOut ? AiProviderException.FailureKind.TIMEOUT : AiProviderException.FailureKind.NETWORK);
        }
    }

    /** @author heyu */
    private static final class ResponseTooLarge extends RuntimeException {}

    /** Bounded while receiving, before buffering or JSON parsing. @author heyu */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() { return result; }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (bytes.size() + incoming > 300_000) {
                subscription.cancel();
                result.completeExceptionally(new ResponseTooLarge());
                return;
            }
            for (ByteBuffer buffer : buffers) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) { result.completeExceptionally(error); }

        @Override
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }

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
