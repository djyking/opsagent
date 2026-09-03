package com.opsagent.rag;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.*;

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

    RagController(RagService service, RagRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
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
}
