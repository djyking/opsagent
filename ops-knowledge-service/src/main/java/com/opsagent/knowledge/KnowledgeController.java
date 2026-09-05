package com.opsagent.knowledge;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.*;

/**
 * 提供知识库、文档上传、解析、切片和内部检索接口。
 *
 * @author heyu
 * @since 2026/8/17
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;

    KnowledgeController(KnowledgeService s) {
        service = s;
    }

    /**
     * 知识库标识请求参数。
     *
     * @author heyu
     * @since 2026/8/17
     */
    record BaseRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 500) String description) {}

    /**
     * 知识审核意见请求。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record ReviewRequest(@Size(max = 1000) String comment) {}

    @PostMapping("/bases")
    ApiResponse<Long> create(@Valid @RequestBody BaseRequest r) {
        return ApiResponse.success(service.createBase(r.name(), r.description()));
    }

    @GetMapping("/bases")
    ApiResponse<List<Map<String, Object>>> bases() {
        return ApiResponse.success(service.bases());
    }

    @GetMapping("/bases/{id}/documents")
    ApiResponse<List<Map<String, Object>>> documents(@PathVariable long id) {
        return ApiResponse.success(service.documents(id));
    }

    @PostMapping("/bases/{id}/documents")
    ApiResponse<Long> upload(
            @PathVariable long id,
            @RequestPart MultipartFile file,
            @RequestParam(required = false) Long ticketId,
            @RequestParam(defaultValue = "PRIVATE") String visibility) {
        return ApiResponse.success(service.upload(id, ticketId, file, visibility));
    }

    @GetMapping("/tickets/{ticketId}/documents")
    ApiResponse<List<Map<String, Object>>> ticketDocuments(@PathVariable long ticketId) {
        return ApiResponse.success(service.ticketDocuments(ticketId));
    }

    @PostMapping("/documents/{id}/parse")
    ApiResponse<Long> parse(@PathVariable long id) {
        return ApiResponse.success(service.requestParse(id));
    }

    @GetMapping("/parse-tasks/{id}")
    ApiResponse<Map<String, Object>> parseTask(@PathVariable long id) {
        return ApiResponse.success(service.parseTask(id));
    }

    @GetMapping("/documents/{id}/chunks")
    ApiResponse<List<Map<String, Object>>> chunks(@PathVariable long id) {
        return ApiResponse.success(service.chunks(id));
    }

    @DeleteMapping("/documents/{id}")
    ApiResponse<KnowledgeService.DeleteResult> delete(@PathVariable long id) {
        return ApiResponse.success(service.deleteDocument(id));
    }

    @GetMapping("/internal/index-tasks/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> indexTask(@PathVariable long id) {
        return ApiResponse.success(service.indexTask(id));
    }

    @GetMapping("/internal/search")
    ApiResponse<List<Map<String, Object>>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) Long ticketId) {
        return ApiResponse.success(service.search(query, topK, documentId, ticketId));
    }

    @PostMapping("/internal/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Integer> reindex() {
        return ApiResponse.success(service.reindexAll());
    }

    @PostMapping("/admin/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Long> requestReindex() {
        return ApiResponse.success(service.requestReindex());
    }

    @GetMapping("/admin/reindex/{taskId}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> reindexTask(@PathVariable long taskId) {
        return ApiResponse.success(service.reindexTask(taskId));
    }

    @GetMapping("/admin/index/consistency")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> indexConsistency() {
        return ApiResponse.success(service.indexConsistency());
    }

    @PostMapping("/admin/index/repair/{documentId}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Long> repairIndex(@PathVariable long documentId) {
        return ApiResponse.success(service.repairIndex(documentId));
    }

    @GetMapping("/admin/index/failed-tasks")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<List<Map<String, Object>>> failedIndexTasks() {
        return ApiResponse.success(service.failedIndexTasks());
    }

    @GetMapping("/internal/debug/search")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<HybridSearchResult> debugSearch(
            @RequestParam String query,
            @RequestParam(defaultValue = "30") int topK,
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) Set<Long> allowedKnowledgeBaseIds,
            @RequestParam(defaultValue = "false") boolean administratorPreview) {
        return ApiResponse.success(service.debugSearch(
                query,
                topK,
                knowledgeBaseId,
                documentId,
                allowedKnowledgeBaseIds,
                administratorPreview));
    }

    @GetMapping("/review/documents")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    ApiResponse<List<Map<String, Object>>> reviewDocuments(
            @RequestParam(required = false) String status) {
        return ApiResponse.success(service.reviewDocuments(status));
    }

    @PostMapping("/documents/{id}/submit-review")
    ApiResponse<Map<String, Object>> submitReview(@PathVariable long id) {
        return ApiResponse.success(service.submitReview(id));
    }

    @PostMapping("/documents/{id}/approve")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    ApiResponse<Map<String, Object>> approve(
            @PathVariable long id, @RequestBody(required = false) ReviewRequest request) {
        return ApiResponse.success(
                service.approveReview(id, request == null ? null : request.comment()));
    }

    @PostMapping("/documents/{id}/reject")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    ApiResponse<Map<String, Object>> reject(
            @PathVariable long id, @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.success(service.rejectReview(id, request.comment()));
    }

    @PostMapping("/documents/{id}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> archive(
            @PathVariable long id, @RequestBody(required = false) ReviewRequest request) {
        return ApiResponse.success(service.archive(id, request == null ? null : request.comment()));
    }

    @GetMapping("/documents/{id}/review-history")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    ApiResponse<List<Map<String, Object>>> reviewHistory(@PathVariable long id) {
        return ApiResponse.success(service.reviewHistory(id));
    }
}
