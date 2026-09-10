package com.opsagent.knowledge;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.constraints.Min;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 面向有权阅读当前文档的用户提供只读正文，禁止浏览器缓存体验私有内容。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@Validated
@RequestMapping("/api/knowledge/documents")
class KnowledgeDocumentContentController {
    private final KnowledgeDocumentContentService service;

    KnowledgeDocumentContentController(KnowledgeDocumentContentService service) {
        this.service = service;
    }

    @GetMapping("/{id}/content")
    ResponseEntity<ApiResponse<KnowledgeDocumentContentService.Content>> content(
            @PathVariable @Min(1) long id,
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(required = false) @Min(1) Integer version) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.page(id, pageNum, version)));
    }
}
