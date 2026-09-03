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
        return invoke(router.selected(), question, request);
    }

    Invocation invoke(LlmClient client, String question, LlmRequest request) {
        long started = System.nanoTime();
        try {
            LlmResult result = client.generate(request);
            long latency = elapsedMillis(started);
            record(client, question, result, latency, true, null);
            metric(client.provider(), "success", latency);
            return new Invocation(result, latency);
        } catch (AiProviderException exception) {
            long latency = elapsedMillis(started);
            String error = exception.statusCode() == 0
                    ? "CONNECTION"
                    : "HTTP_" + exception.statusCode();
            record(client, question, null, latency, false, error);
            metric(client.provider(), "failure", latency);
            throw exception;
        }
    }

    private void record(
            LlmClient client,
            String question,
            LlmResult result,
            long latency,
            boolean success,
            String error) {
        int input = result == null ? 0 : result.inputTokens();
        int output = result == null ? 0 : result.outputTokens();
        usageRepository.save(new AiUsageRepository.AiUsage(
                MDC.get("traceId"),
                SecurityUsers.current().userId(),
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
}
