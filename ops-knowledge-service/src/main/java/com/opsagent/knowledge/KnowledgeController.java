package com.opsagent.knowledge;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.constraints.*;

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
    record BaseRequest(@NotBlank String name, String description) {}

    @PostMapping("/bases")
    ApiResponse<Long> create(@RequestBody BaseRequest r) {
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
            @RequestParam(defaultValue = "PRIVATE") String visibility) {
        return ApiResponse.success(service.upload(id, file, visibility));
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
            @RequestParam(required = false) Long documentId) {
        return ApiResponse.success(service.search(query, topK, documentId));
    }

    @PostMapping("/internal/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Integer> reindex() {
        return ApiResponse.success(service.reindexAll());
    }
}
