package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 将模型 Token、来源和最终校验结果编码为可观测的 SSE 事件流。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class RagStreamingService {
    private static final Logger LOG = LoggerFactory.getLogger(RagStreamingService.class);
    private final RagService ragService;
    private final AiProperties properties;
    private final Executor executor;

    RagStreamingService(
            RagService ragService,
            AiProperties properties,
            @Qualifier("ragStreamExecutor") Executor executor) {
        this.ragService = ragService;
        this.properties = properties;
        this.executor = executor;
    }

    SseEmitter open(RagService.StreamPlan plan, LlmInvocationService.AuditContext context) {
        return open(plan, context, answer -> {}, message -> {});
    }

    SseEmitter open(
            RagService.StreamPlan plan,
            LlmInvocationService.AuditContext context,
            Consumer<RagService.Answer> onComplete,
            Consumer<String> onError) {
        return openPrepared(phase -> plan, context, null, onComplete, onError, false);
    }

    SseEmitter openPrepared(
            Function<Consumer<String>, RagService.StreamPlan> preparation,
            LlmInvocationService.AuditContext context,
            AnswerStyle style,
            Consumer<RagService.Answer> onComplete,
            Consumer<String> onError) {
        return openPrepared(preparation, context, style, onComplete, onError, true);
    }

    private SseEmitter openPrepared(
            Function<Consumer<String>, RagService.StreamPlan> preparation,
            LlmInvocationService.AuditContext context,
            AnswerStyle style,
            Consumer<RagService.Answer> onComplete,
            Consumer<String> onError,
            boolean measure) {
        SseEmitter emitter = new SseEmitter(properties.streamTimeoutMillis());
        AtomicBoolean settled = new AtomicBoolean();
        AtomicBoolean errorNotified = new AtomicBoolean();
        AtomicReference<FutureTask<Void>> task = new AtomicReference<>();
        // Explicit snapshot: pooled workers must not retain another request's actor or token relay.
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var requestAttributes = RequestContextHolder.getRequestAttributes();
        var loggingContext = MDC.getCopyOfContextMap();
        Timeline timeline = measure ? new Timeline() : null;
        Consumer<String> notifyError =
                message -> {
                    if (errorNotified.compareAndSet(false, true)) {
                        try {
                            onError.accept(message);
                        } catch (RuntimeException exception) {
                            LOG.warn("RAG 会话失败状态未能保存");
                        }
                    }
                };
        emitter.onTimeout(
                () -> {
                    if (settled.compareAndSet(false, true)) {
                        if (task.get() != null) task.get().cancel(true);
                        notifyError.accept("生成超时，回答未完成。");
                        sendError(emitter, "生成超时，回答未完成，请重新生成。");
                    }
                });
        emitter.onError(
                cause -> {
                    if (settled.compareAndSet(false, true)) {
                        if (task.get() != null) task.get().cancel(true);
                        notifyError.accept("连接中断，回答未完成。");
                    }
                });
        emitter.onCompletion(
                () -> {
                    if (settled.compareAndSet(false, true)) {
                        if (task.get() != null) task.get().cancel(true);
                        notifyError.accept("连接已关闭，回答未完成。");
                    }
                });
        try {
            if (measure) send(emitter, "status", Map.of("phase", "preparing"));
            FutureTask<Void> worker =
                    new FutureTask<>(
                            () -> {
                                var previousSecurity = SecurityContextHolder.getContext();
                                var previousRequest = RequestContextHolder.getRequestAttributes();
                                var previousLogging = MDC.getCopyOfContextMap();
                                try {
                                    var security = SecurityContextHolder.createEmptyContext();
                                    security.setAuthentication(authentication);
                                    SecurityContextHolder.setContext(security);
                                    RequestContextHolder.setRequestAttributes(requestAttributes);
                                    if (loggingContext == null) MDC.clear();
                                    else MDC.setContextMap(loggingContext);
                                    run(
                                            emitter,
                                            preparation,
                                            context,
                                            style,
                                            timeline,
                                            onComplete,
                                            notifyError,
                                            settled);
                                } finally {
                                    SecurityContextHolder.setContext(previousSecurity);
                                    if (previousRequest == null)
                                        RequestContextHolder.resetRequestAttributes();
                                    else RequestContextHolder.setRequestAttributes(previousRequest);
                                    if (previousLogging == null) MDC.clear();
                                    else MDC.setContextMap(previousLogging);
                                }
                                return null;
                            });
            task.set(worker);
            if (!settled.get()) executor.execute(worker);
        } catch (RejectedExecutionException exception) {
            settled.set(true);
            String message = "当前问答人数较多，请稍后重试。";
            notifyError.accept(message);
            sendError(emitter, message);
        }
        return emitter;
    }

    SseEmitter error(String message) {
        SseEmitter emitter = new SseEmitter(10_000L);
        // SseEmitter buffers this short error until MVC attaches the response handler.
        // Reporting a rejected request must not need another worker from the saturated pool.
        sendError(emitter, message);
        return emitter;
    }

    private void run(
            SseEmitter emitter,
            Function<Consumer<String>, RagService.StreamPlan> preparation,
            LlmInvocationService.AuditContext context,
            AnswerStyle style,
            Timeline timeline,
            Consumer<RagService.Answer> onComplete,
            Consumer<String> onError,
            AtomicBoolean settled) {
        try {
            if (settled.get()) return;
            RagService.StreamPlan plan =
                    preparation.apply(
                            phase -> {
                                if (settled.get() || Thread.currentThread().isInterrupted())
                                    throw new StreamWriteException(null);
                                if (timeline != null) timeline.phase(phase);
                                send(emitter, "status", Map.of("phase", phase));
                            });
            if (settled.get() || Thread.currentThread().isInterrupted()) return;
            if (timeline != null) timeline.prepared();
            send(
                    emitter,
                    "status",
                    Map.of(
                            "phase",
                            "CMDB".equals(plan.metadata().retrievalMode())
                                    ? "cmdb"
                                    : plan.immediate() == null ? "generating" : "retrieval-only"));
            Consumer<String> output =
                    delta -> {
                        if (settled.get()) throw new StreamWriteException(null);
                        send(emitter, "token", Map.of("delta", delta));
                    };
            RagService.Answer answer =
                    timeline == null
                            ? ragService.stream(plan, output, context)
                            : ragService.stream(plan, output, context, timeline::modelContent);
            if (timeline != null)
                answer = answer.withPresentation(AnswerStyle.orDefault(style), timeline.finish());
            if (!settled.compareAndSet(false, true)) return;
            onComplete.accept(answer);
            send(emitter, "sources", Map.of("references", answer.references()));
            send(emitter, "done", answer);
            emitter.complete();
        } catch (StreamWriteException exception) {
            settled.set(true);
            onError.accept("连接中断，回答未完成。");
            emitter.complete();
        } catch (AiProviderException exception) {
            settled.set(true);
            onError.accept(exception.getMessage());
            sendError(emitter, exception.getMessage());
        } catch (BusinessException exception) {
            settled.set(true);
            onError.accept(exception.getMessage());
            sendError(emitter, exception.getMessage(), exception.getErrorCode().code());
        } catch (RuntimeException exception) {
            settled.set(true);
            onError.accept("流式问答未完成，请稍后重试。");
            sendError(emitter, "流式问答暂时不可用，请稍后重试。");
        }
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException exception) {
            throw new StreamWriteException(exception);
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        sendError(emitter, message, 50000);
    }

    private void sendError(SseEmitter emitter, String message, int code) {
        try {
            send(emitter, "error", Map.of("message", message, "code", code));
        } catch (StreamWriteException ignored) {
            // 客户端已经断开时不再尝试二次写入。
        } finally {
            emitter.complete();
        }
    }

    /**
     * All timestamps share a monotonic clock and exclude subsequent browser history refresh.
     *
     * @author heyu
     * @since 2026/9/3
     */
    private static final class Timeline {
        private final long started = System.nanoTime();
        private long phaseStarted = started;
        private long prepared;
        private long firstToken;
        private String currentPhase = "preparing";
        private final Map<String, Long> phases = new LinkedHashMap<>();

        void phase(String phase) {
            long now = System.nanoTime();
            phases.merge(currentPhase, (now - phaseStarted) / 1_000_000L, Long::sum);
            currentPhase = phase;
            phaseStarted = now;
        }

        void prepared() {
            phase("generation");
            prepared = phaseStarted;
        }

        void modelContent() {
            if (firstToken == 0) firstToken = System.nanoTime();
        }

        RagService.AnswerTiming finish() {
            long finished = System.nanoTime();
            phases.merge(currentPhase, (finished - phaseStarted) / 1_000_000L, Long::sum);
            return new RagService.AnswerTiming(
                    (finished - started) / 1_000_000L,
                    (prepared - started) / 1_000_000L,
                    (finished - prepared) / 1_000_000L,
                    firstToken == 0 ? null : (firstToken - started) / 1_000_000L,
                    Map.copyOf(phases));
        }
    }

    /**
     * 表示浏览器断开或 SSE 响应已经结束，不触发供应商重试。
     *
     * @author heyu
     * @since 2026/9/3
     */
    private static final class StreamWriteException extends RuntimeException {
        StreamWriteException(Throwable cause) {
            super(cause);
        }
    }
}
