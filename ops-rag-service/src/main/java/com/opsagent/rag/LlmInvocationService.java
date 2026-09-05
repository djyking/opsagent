package com.opsagent.rag;

import com.opsagent.common.security.SecurityUsers;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 统一模型调用的指标、耗时和最小化 Usage 审计。
 *
 * @author heyu
 * @since 2026/9/1
 */
@Service
public class LlmInvocationService {
    private final LlmClientRouter router;
    private final MeterRegistry registry;
    private final AiUsageRepository usageRepository;

    LlmInvocationService(
            LlmClientRouter router,
            MeterRegistry registry,
            AiUsageRepository usageRepository) {
        this.router = router;
        this.registry = registry;
        this.usageRepository = usageRepository;
    }

    Invocation invoke(String question, LlmRequest request) {
        return invoke(router.selected(), question, request, currentContext());
    }

    Invocation invoke(LlmClient client, String question, LlmRequest request) {
        return invoke(client, question, request, currentContext());
    }

    private Invocation invoke(
            LlmClient client,
            String question,
            LlmRequest request,
            AuditContext context) {
        long started = System.nanoTime();
        try {
            LlmResult result = client.generate(request);
            long latency = elapsedMillis(started);
            record(client, question, result, latency, result.generationComplete(),
                    result.generationComplete() ? null : "INCOMPLETE_" + result.finishReason(), context);
            metric(client.provider(), result.generationComplete() ? "success" : "incomplete", latency);
            return new Invocation(result, latency);
        } catch (AiProviderException exception) {
            long latency = elapsedMillis(started);
            String error = exception.statusCode() == 0
                    ? "CONNECTION"
                    : "HTTP_" + exception.statusCode();
            record(client, question, null, latency, false, error, context);
            metric(client.provider(), "failure", latency);
            throw exception;
        }
    }

    Invocation stream(
            String question,
            LlmRequest request,
            Consumer<String> onDelta,
            AuditContext context) {
        LlmClient client = router.selected();
        long started = System.nanoTime();
        try {
            LlmResult result = client.stream(request, onDelta);
            long latency = elapsedMillis(started);
            record(client, question, result, latency, result.generationComplete(),
                    result.generationComplete() ? null : "INCOMPLETE_" + result.finishReason(), context);
            metric(client.provider(), result.generationComplete() ? "success" : "incomplete", latency);
            return new Invocation(result, latency);
        } catch (AiProviderException exception) {
            long latency = elapsedMillis(started);
            String error = exception.statusCode() == 0
                    ? "CONNECTION"
                    : "HTTP_" + exception.statusCode();
            record(client, question, null, latency, false, error, context);
            metric(client.provider(), "failure", latency);
            throw exception;
        }
    }

    AuditContext currentContext() {
        return new AuditContext(SecurityUsers.current().userId(), MDC.get("traceId"));
    }

    private void record(
            LlmClient client,
            String question,
            LlmResult result,
            long latency,
            boolean success,
            String error,
            AuditContext context) {
        int input = result == null ? 0 : result.inputTokens();
        int output = result == null ? 0 : result.outputTokens();
        usageRepository.save(new AiUsageRepository.AiUsage(
                context.traceId(),
                context.userId(),
                client.provider(),
                client.model(),
                hash(question),
                input,
                output,
                latency,
                success,
                error));
    }

    private void metric(String provider, String outcome, long latency) {
        registry.counter("opsagent_ai_requests_total", "provider", provider, "outcome", outcome)
                .increment();
        Timer.builder("opsagent_ai_request_duration")
                .tag("provider", provider)
                .register(registry)
                .record(latency, TimeUnit.MILLISECONDS);
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /**
     * 将统一模型结果和本地实测耗时一起返回给问答编排层。
     *
     * @author heyu
     * @since 2026/9/1
     */
    record Invocation(LlmResult result, long latencyMs) {}

    /**
     * 保存请求线程中的最小化审计上下文，供 SSE 工作线程安全使用。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record AuditContext(long userId, String traceId) {}
}
