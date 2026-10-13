package com.opsagent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 读取 AI 供应商的 SSE 响应，并在首个 Token 前执行有限重试。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class AiStreamHttpExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(AiStreamHttpExecutor.class);
    private final ObjectMapper mapper;

    AiStreamHttpExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    boolean post(
            String provider,
            String baseUrl,
            String path,
            String apiKey,
            Map<String, Object> body,
            int timeoutSeconds,
            int maximumAttempts,
            StreamEventHandler handler) {
        int attempts = Math.max(1, Math.min(maximumAttempts, 3));
        AiProviderException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            AtomicBoolean emitted = new AtomicBoolean();
            AtomicInteger responseStatus = new AtomicInteger();
            AssistantTokenBudget.Reservation reservation;
            try {
                reservation = AssistantTokenBudget.reserve(provider, body);
            } catch (AiProviderException rejected) {
                if (rejected.kind() == AiProviderException.FailureKind.BUDGET
                        && lastFailure != null) {
                    LOG.warn(
                            "AI stream retry stopped: provider={}, nextAttempt={}, reason=BUDGET,"
                                + " originalKind={}, originalStatus={}, transport={}, traceId={}",
                            provider,
                            attempt,
                            lastFailure.kind(),
                            lastFailure.statusCode(),
                            lastFailure.diagnosticCode(),
                            traceId());
                    // Admission did not send a request: preserve the last actual failure and keep
                    // every unknown-usage reservation charged. Budget evidence remains separate.
                    throw lastFailure;
                }
                throw rejected;
            }
            long started = System.nanoTime();
            final JsonNode[] usage = {null};
            AtomicBoolean responseTerminal = new AtomicBoolean();
            try {
                boolean done =
                        streamOnce(
                                provider,
                                baseUrl,
                                path,
                                apiKey,
                                reservation.body(),
                                timeoutSeconds,
                                event -> {
                                    if (event.hasNonNull("usage")) usage[0] = event.path("usage");
                                    if (event.path("response").hasNonNull("usage"))
                                        usage[0] = event.path("response").path("usage");
                                    if (java.util.Set.of(
                                                    "response.completed",
                                                    "response.incomplete",
                                                    "response.failed")
                                            .contains(event.path("type").asText()))
                                        responseTerminal.set(true);
                                    return handler.handle(event);
                                },
                                emitted,
                                responseStatus);
                reservation.finish(done || responseTerminal.get() ? usage[0] : null);
                return done;
            } catch (AiProviderException exception) {
                lastFailure = exception;
                failureLog(
                        provider, attempt, responseStatus.get(), emitted.get(), started, exception);
                boolean retryable =
                        exception.kind() != AiProviderException.FailureKind.BUDGET
                                && exception.kind() != AiProviderException.FailureKind.CANCELLED
                                && (exception.statusCode() == 429
                                        || exception.statusCode() >= 500
                                        || exception.statusCode() == 0);
                if (emitted.get() || !retryable || attempt == attempts) {
                    throw exception;
                }
                pause(provider, attempt);
            } catch (ResourceAccessException exception) {
                String diagnostic = AiTransportDiagnostics.code(exception);
                lastFailure =
                        new AiProviderException(
                                provider,
                                0,
                                diagnostic.endsWith("TIMEOUT")
                                        ? "AI 服务响应超时，请稍后重试。"
                                        : "AI 服务网络连接失败，请稍后重试。",
                                exception,
                                diagnostic.endsWith("TIMEOUT")
                                        ? AiProviderException.FailureKind.TIMEOUT
                                        : AiProviderException.FailureKind.NETWORK,
                                diagnostic,
                                responseStatus.get() > 0);
                failureLog(
                        provider,
                        attempt,
                        responseStatus.get(),
                        emitted.get(),
                        started,
                        lastFailure);
                if (emitted.get() || attempt == attempts) {
                    throw lastFailure;
                }
                pause(provider, attempt);
            } finally {
                reservation.finish(null);
            }
        }
        throw new AiProviderException(provider, 0, "AI 流式响应未完成。", null);
    }

    private boolean streamOnce(
            String provider,
            String baseUrl,
            String path,
            String apiKey,
            Map<String, Object> body,
            int timeoutSeconds,
            StreamEventHandler handler,
            AtomicBoolean emitted,
            AtomicInteger responseStatus) {
        RestClient client = client(timeoutSeconds);
        Boolean done =
                client.post()
                        .uri(normalize(baseUrl) + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .headers(headers -> headers.setBearerAuth(apiKey))
                        .body(body)
                        .exchange(
                                (request, response) -> {
                                    int status = response.getStatusCode().value();
                                    responseStatus.set(status);
                                    if (status < 200 || status >= 300) {
                                        throw failure(provider, status);
                                    }
                                    return readEvents(
                                            provider, response.getBody(), handler, emitted);
                                });
        return Boolean.TRUE.equals(done);
    }

    private boolean readEvents(
            String provider,
            java.io.InputStream input,
            StreamEventHandler handler,
            AtomicBoolean emitted) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (dispatch(provider, data, handler, emitted)) return true;
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
            return dispatch(provider, data, handler, emitted);
        } catch (IOException exception) {
            String diagnostic = AiTransportDiagnostics.code(exception);
            throw new AiProviderException(
                    provider,
                    0,
                    "AI 流式响应读取失败。",
                    exception,
                    diagnostic.endsWith("TIMEOUT")
                            ? AiProviderException.FailureKind.TIMEOUT
                            : AiProviderException.FailureKind.NETWORK,
                    diagnostic,
                    true);
        }
    }

    private boolean dispatch(
            String provider,
            StringBuilder data,
            StreamEventHandler handler,
            AtomicBoolean emitted) {
        if (data.isEmpty()) return false;
        if ("[DONE]".contentEquals(data)) return true;
        try {
            JsonNode event = mapper.readTree(data.toString());
            if (handler.handle(event)) {
                emitted.set(true);
            }
            return false;
        } catch (JsonProcessingException exception) {
            throw new AiProviderException(
                    provider,
                    502,
                    "AI 服务返回了无效的流式事件。",
                    exception,
                    AiProviderException.FailureKind.PROTOCOL,
                    "INVALID_SSE_JSON",
                    true);
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
        return new AiProviderException(
                provider,
                status,
                message,
                null,
                AiProviderException.FailureKind.HTTP,
                "HTTP",
                true);
    }

    private void failureLog(
            String provider,
            int attempt,
            int http,
            boolean emitted,
            long started,
            AiProviderException failure) {
        // Fixed categories/class names only. Never log exception messages or HTTP/body contents.
        LOG.warn(
                "AI stream attempt failed: provider={}, attempt={}, http={}, kind={},"
                    + " errorStatus={}, transport={}, contentEmitted={}, elapsedMs={},"
                    + " causeTypes={}, traceId={}",
                provider,
                attempt,
                http,
                failure.kind(),
                failure.statusCode(),
                failure.diagnosticCode(),
                emitted,
                (System.nanoTime() - started) / 1_000_000L,
                AiTransportDiagnostics.causeTypes(failure),
                traceId());
    }

    private String traceId() {
        String trace = MDC.get("traceId");
        return trace != null && trace.matches("[A-Za-z0-9_-]{1,64}") ? trace : "UNAVAILABLE";
    }

    private String normalize(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private void pause(String provider, int attempt) {
        try {
            Thread.sleep(250L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException(
                    provider, 0, "AI 请求已取消。", exception, AiProviderException.FailureKind.CANCELLED);
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
