package com.opsagent.knowledge;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 审核专用预览与源文件下载；未发布文档不进入普通用户或访客接口。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Validated
@RestController
@RequestMapping("/api/knowledge/review/documents/{id}")
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
class KnowledgeReviewContentController {
    private final KnowledgeReviewContentService service;

    KnowledgeReviewContentController(KnowledgeReviewContentService service) { this.service = service; }

    @GetMapping("/preview")
    ResponseEntity<ApiResponse<KnowledgeReviewContentService.Preview>> preview(@PathVariable @Min(1) long id,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int pageNum,
            @RequestParam(defaultValue = "8") @Min(1) @Max(50) int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.preview(id, pageNum, pageSize)));
    }

    @GetMapping("/text")
    ResponseEntity<ApiResponse<KnowledgeReviewContentService.ParsedText>> text(@PathVariable @Min(1) long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.text(id)));
    }

    @GetMapping("/source")
    ResponseEntity<Resource> source(@PathVariable @Min(1) long id) {
        var source = service.source(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(source.originalName(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(source.path()));
    }
}
