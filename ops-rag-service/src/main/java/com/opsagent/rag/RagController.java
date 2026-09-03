package com.opsagent.rag;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
            RagService service,
            RagRateLimiter rateLimiter,
            RagStreamingService streamingService) {
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
            @Min(1) Long documentId) {}

    @PostMapping({"/ask", "/chat"})
    ApiResponse<RagService.Answer> chat(@Valid @RequestBody ChatRequest r) {
        rateLimiter.check();
        return ApiResponse.success(service.ask(r.question(), r.topK(), r.documentId()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        try {
            rateLimiter.check();
            RagService.StreamPlan plan = service.prepareStream(
                    request.question(), request.topK(), request.documentId());
            return streamingService.open(plan, service.auditContext());
        } catch (BusinessException exception) {
            return streamingService.error(exception.getMessage());
        }
    }
}
