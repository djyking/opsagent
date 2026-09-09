package com.opsagent.rag;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.SecurityUsers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 私有问答会话接口，所有请求均使用服务端认证的账号身份。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@Validated
@RequestMapping("/api/rag/conversations")
public class RagConversationController {
    private final RagConversationService conversations;
    private final RagService rag;
    private final RagStreamingService streaming;
    private final RagRateLimiter limiter;

    RagConversationController(
            RagConversationService conversations,
            RagService rag,
            RagStreamingService streaming,
            RagRateLimiter limiter) {
        this.conversations = conversations;
        this.rag = rag;
        this.streaming = streaming;
        this.limiter = limiter;
    }

    /**
     * 创建私有会话的标题。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record CreateRequest(@Size(max = 120) String title) {}

    /**
     * 重命名现有会话。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record RenameRequest(@NotBlank @Size(max = 120) String title) {}

    /**
     * 一轮问答请求，不接收客户端提交的账号或历史答案。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record QuestionRequest(
            @NotBlank @Size(max = 2000) String question,
            @Min(1) @Max(20) Integer topK,
            @Min(1) Long documentId,
            @Min(1) Long ticketId,
            @Size(max = 20) String provider,
            @Valid ObservabilityContext observabilityContext,
            AnswerStyle answerStyle) {
        QuestionRequest(
                String question, Integer topK, Long documentId, Long ticketId, String provider) {
            this(question, topK, documentId, ticketId, provider, null, null);
        }

        QuestionRequest(
                String question,
                Integer topK,
                Long documentId,
                Long ticketId,
                String provider,
                ObservabilityContext observabilityContext) {
            this(question, topK, documentId, ticketId, provider, observabilityContext, null);
        }
    }

    @GetMapping
    ApiResponse<RagConversationService.ConversationPage> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize) {
        return ApiResponse.success(
                conversations.list(SecurityUsers.current().userId(), page, pageSize));
    }

    @PostMapping
    ApiResponse<RagConversationService.Conversation> create(
            @Valid @RequestBody CreateRequest request) {
        return ApiResponse.success(
                conversations.create(SecurityUsers.current().userId(), request.title()));
    }

    @PatchMapping("/{id}")
    ApiResponse<RagConversationService.Conversation> rename(
            @PathVariable String id, @Valid @RequestBody RenameRequest request) {
        return ApiResponse.success(
                conversations.rename(id, SecurityUsers.current().userId(), request.title()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable String id) {
        conversations.delete(id, SecurityUsers.current().userId());
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/messages")
    ApiResponse<RagConversationService.TurnPage> messages(
            @PathVariable String id, @RequestParam(required = false) @Min(1) Long beforeId) {
        return ApiResponse.success(
                conversations.turns(id, SecurityUsers.current().userId(), beforeId));
    }

    @PostMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter ask(@PathVariable String id, @Valid @RequestBody QuestionRequest request) {
        long userId = SecurityUsers.current().userId();
        conversations.owned(id, userId);
        limiter.check();
        var scope = limiter.requestScope();
        long startedTurn = -1;
        try {
            long turnId = conversations.begin(id, userId, request.question().trim());
            startedTurn = turnId;
            return streaming.openPrepared(
                    progress -> {
                        progress.accept("history");
                        String context = conversations.context(id, userId);
                        return rag.prepareStream(
                                request.question().trim(),
                                request.topK(),
                                request.documentId(),
                                request.ticketId(),
                                context,
                                request.provider(),
                                request.observabilityContext(),
                                request.answerStyle(),
                                progress);
                    },
                    rag.auditContext(),
                    request.answerStyle(),
                    answer -> {
                        try {
                            conversations.complete(id, userId, turnId, answer);
                        } catch (RuntimeException exception) {
                            scope.failure(exception);
                            throw exception;
                        } finally {
                            scope.close();
                        }
                    },
                    message -> {
                        try {
                            conversations.fail(id, userId, turnId, message);
                        } finally {
                            scope.failure(new IllegalStateException("RAG conversation incomplete"));
                        }
                    });
        } catch (RuntimeException exception) {
            try {
                if (startedTurn >= 0)
                    conversations.fail(id, userId, startedTurn, "知识检索或生成准备失败，请重试");
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
            } finally {
                scope.failure(exception);
            }
            throw exception;
        }
    }
}
