package com.opsagent.knowledge;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.constraints.*;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    ApiResponse<Long> upload(@PathVariable long id, @RequestPart MultipartFile file) {
        return ApiResponse.success(service.upload(id, file));
    }

    @PostMapping("/documents/{id}/parse")
    ApiResponse<Void> parse(@PathVariable long id) {
        service.parse(id);
        return ApiResponse.success();
    }

    @GetMapping("/documents/{id}/chunks")
    ApiResponse<List<Map<String, Object>>> chunks(@PathVariable long id) {
        return ApiResponse.success(service.chunks(id));
    }

    @GetMapping("/internal/search")
    ApiResponse<List<Map<String, Object>>> search(
            @RequestParam String query, @RequestParam(defaultValue = "5") int topK) {
        return ApiResponse.success(service.search(query, topK));
    }
}
