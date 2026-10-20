package com.opsagent.rag;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 提供基于知识库引用的问答接口。
 *
 * @author heyu
 * @since 2026/8/26
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {
    private final RagService service;
    private final RagRateLimiter rateLimiter;
    private final RagStreamingService streamingService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private HarnessAnswerService harness;

    RagController(
            RagService service, RagRateLimiter rateLimiter, RagStreamingService streamingService) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.streamingService = streamingService;
    }

    /**
     * 检索增强问答请求参数。
     *
     * @author heyu
     * @since 2026/8/26
     */
    record ChatRequest(
            @NotBlank @Size(max = 2000) String question,
            @Min(1) @Max(20) Integer topK,
            @Min(1) Long documentId,
            @Min(1) Long ticketId,
            @Size(max = 20) String provider,
            @Valid ObservabilityContext observabilityContext,
            AnswerStyle answerStyle) {
        ChatRequest(
                String question, Integer topK, Long documentId, Long ticketId, String provider) {
            this(question, topK, documentId, ticketId, provider, null, null);
        }

        ChatRequest(
                String question,
                Integer topK,
                Long documentId,
                Long ticketId,
                String provider,
                ObservabilityContext observabilityContext) {
            this(question, topK, documentId, ticketId, provider, observabilityContext, null);
        }
    }

    @PostMapping({"/ask", "/chat"})
    org.springframework.http.ResponseEntity<?> routedChat(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ChatRequest request) {
        if (harness == null) return org.springframework.http.ResponseEntity.ok(chat(request));
        rateLimiter.check();
        var scope = rateLimiter.requestScope();
        try {
            return HarnessAnswerController.response(
                    harness.submit(authorization, key, request, () -> legacyAnswer(request)));
        } catch (RuntimeException failure) {
            scope.failure(failure);
            throw failure;
        } finally {
            scope.close();
        }
    }

    ApiResponse<RagService.Answer> chat(@Valid @RequestBody ChatRequest r) {
        rateLimiter.check();
        var scope = rateLimiter.requestScope();
        try {
            return ApiResponse.success(
                    r.answerStyle() != null
                            ? service.ask(
                                    r.question(),
                                    r.topK(),
                                    r.documentId(),
                                    r.ticketId(),
                                    r.provider(),
                                    r.observabilityContext(),
                                    r.answerStyle())
                            : r.observabilityContext() == null
                                    ? service.ask(
                                            r.question(),
                                            r.topK(),
                                            r.documentId(),
                                            r.ticketId(),
                                            r.provider())
                                    : service.ask(
                                            r.question(),
                                            r.topK(),
                                            r.documentId(),
                                            r.ticketId(),
                                            r.provider(),
                                            r.observabilityContext()));
        } catch (RuntimeException exception) {
            scope.failure(exception);
            throw exception;
        } finally {
            scope.close();
        }
    }

    private RagService.Answer legacyAnswer(ChatRequest request) {
        return service.ask(
                request.question(),
                request.topK(),
                request.documentId(),
                request.ticketId(),
                request.provider(),
                request.observabilityContext(),
                request.answerStyle());
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        RagRateLimiter.Scope scope = null;
        try {
            rateLimiter.check();
            scope = rateLimiter.requestScope();
            var activeScope = scope;
            return streamingService.openPrepared(
                    progress ->
                            service.prepareStream(
                                    request.question(),
                                    request.topK(),
                                    request.documentId(),
                                    request.ticketId(),
                                    null,
                                    request.provider(),
                                    request.observabilityContext(),
                                    request.answerStyle(),
                                    progress),
                    service.auditContext(),
                    request.answerStyle(),
                    answer -> activeScope.close(),
                    message ->
                            activeScope.failure(
                                    new IllegalStateException("RAG stream incomplete")));
        } catch (BusinessException exception) {
            if (scope != null) scope.failure(exception);
            if (request.observabilityContext() != null
                    && java.util.Set.of(
                                    ErrorCode.UNAUTHENTICATED,
                                    ErrorCode.FORBIDDEN,
                                    ErrorCode.NOT_FOUND,
                                    ErrorCode.CONFLICT,
                                    ErrorCode.VALIDATION)
                            .contains(exception.getErrorCode())) throw exception;
            return streamingService.error(exception.getMessage());
        } catch (RuntimeException exception) {
            if (scope != null) scope.failure(exception);
            throw exception;
        }
    }

    @GetMapping("/debug/search")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> debugSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "30") @Min(1) @Max(30) int topK,
            @RequestParam(required = false) Long documentId) {
        return ApiResponse.success(service.debugSearch(query, topK, documentId));
    }
}
