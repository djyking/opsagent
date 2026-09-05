package com.opsagent.rag;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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

    SseEmitter open(
            RagService.StreamPlan plan,
            LlmInvocationService.AuditContext context) {
        return open(plan, context, answer -> {}, message -> {});
    }

    SseEmitter open(
            RagService.StreamPlan plan,
            LlmInvocationService.AuditContext context,
            Consumer<RagService.Answer> onComplete,
            Consumer<String> onError) {
        SseEmitter emitter = new SseEmitter(properties.streamTimeoutMillis());
        AtomicBoolean settled = new AtomicBoolean();
        AtomicBoolean errorNotified = new AtomicBoolean();
        Consumer<String> notifyError = message -> {
            if (errorNotified.compareAndSet(false, true)) {
                try { onError.accept(message); }
                catch (RuntimeException exception) { LOG.warn("RAG 会话失败状态未能保存"); }
            }
        };
        emitter.onTimeout(() -> {
            if (settled.compareAndSet(false, true)) {
                notifyError.accept("生成超时，回答未完成。");
                sendError(emitter, "生成超时，回答未完成，请重新生成。");
            }
        });
        emitter.onError(cause -> {
            if (settled.compareAndSet(false, true)) notifyError.accept("连接中断，回答未完成。");
        });
        emitter.onCompletion(() -> {
            if (settled.compareAndSet(false, true)) notifyError.accept("连接已关闭，回答未完成。");
        });
        executor.execute(() -> run(emitter, plan, context, onComplete, notifyError, settled));
        return emitter;
    }

    SseEmitter error(String message) {
        SseEmitter emitter = new SseEmitter(10_000L);
        executor.execute(() -> sendError(emitter, message));
        return emitter;
    }

    private void run(
            SseEmitter emitter,
            RagService.StreamPlan plan,
            LlmInvocationService.AuditContext context,
            Consumer<RagService.Answer> onComplete,
            Consumer<String> onError,
            AtomicBoolean settled) {
        try {
            if (settled.get()) return;
            send(emitter, "status", Map.of(
                    "phase", "CMDB".equals(plan.metadata().retrievalMode()) ? "cmdb"
                            : plan.immediate() == null ? "generating" : "retrieval-only"));
            RagService.Answer answer = ragService.stream(
                    plan,
                    delta -> {
                        if (settled.get()) throw new StreamWriteException(null);
                        send(emitter, "token", Map.of("delta", delta));
                    },
                    context);
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
        try {
            send(emitter, "error", Map.of("message", message));
        } catch (StreamWriteException ignored) {
            // 客户端已经断开时不再尝试二次写入。
        } finally {
            emitter.complete();
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
