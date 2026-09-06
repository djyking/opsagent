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
            @Valid ObservabilityContext observabilityContext) {
        ChatRequest(
                String question, Integer topK, Long documentId, Long ticketId, String provider) {
            this(question, topK, documentId, ticketId, provider, null);
        }
    }

    @PostMapping({"/ask", "/chat"})
    ApiResponse<RagService.Answer> chat(@Valid @RequestBody ChatRequest r) {
        rateLimiter.check();
        var scope = rateLimiter.requestScope();
        try {
            return ApiResponse.success(
                    r.observabilityContext() == null
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

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        RagRateLimiter.Scope scope = null;
        try {
            rateLimiter.check();
            scope = rateLimiter.requestScope();
            RagService.StreamPlan plan =
                    request.observabilityContext() == null
                            ? service.prepareStream(
                                    request.question(),
                                    request.topK(),
                                    request.documentId(),
                                    request.ticketId(),
                                    null,
                                    request.provider())
                            : service.prepareStream(
                                    request.question(),
                                    request.topK(),
                                    request.documentId(),
                                    request.ticketId(),
                                    null,
                                    request.provider(),
                                    request.observabilityContext());
            var activeScope = scope;
            return streamingService.open(
                    plan,
                    service.auditContext(),
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
