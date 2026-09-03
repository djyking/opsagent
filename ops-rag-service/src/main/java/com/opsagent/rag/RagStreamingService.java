package com.opsagent.rag;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 将模型 Token、来源和最终校验结果编码为可观测的 SSE 事件流。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class RagStreamingService {
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
        long timeout = (Math.max(3, properties.getTimeoutSeconds()) + 30L) * 1000L;
        SseEmitter emitter = new SseEmitter(timeout);
        executor.execute(() -> run(emitter, plan, context));
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
            LlmInvocationService.AuditContext context) {
        try {
            send(emitter, "status", Map.of("phase", "generating"));
            RagService.Answer answer = ragService.stream(
                    plan,
                    delta -> send(emitter, "token", Map.of("delta", delta)),
                    context);
            send(emitter, "sources", Map.of("references", answer.references()));
            send(emitter, "done", answer);
            emitter.complete();
        } catch (StreamWriteException exception) {
            emitter.complete();
        } catch (AiProviderException exception) {
            sendError(emitter, exception.getMessage());
        } catch (RuntimeException exception) {
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
