package com.example.opsagent.opsagent.knowledgebase.controller;

import com.example.opsagent.opsagent.common.ApiResponse;
import com.example.opsagent.opsagent.common.PageResponse;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseCreateRequest;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseQueryRequest;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseResponse;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseUpdateRequest;
import com.example.opsagent.opsagent.knowledgebase.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> create(@Valid @RequestBody KnowledgeBaseCreateRequest request) {
        return ApiResponse.success(knowledgeBaseService.createKnowledgeBase(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> update(@PathVariable Long id, @Valid @RequestBody KnowledgeBaseUpdateRequest request) {
        request.setId(id);
        return ApiResponse.success(knowledgeBaseService.updateKnowledgeBase(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @NotNull Long id) {
        knowledgeBaseService.deleteKnowledgeBase(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> detail(@PathVariable @NotNull Long id) {
        return ApiResponse.success(knowledgeBaseService.getKnowledgeBase(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<KnowledgeBaseResponse>> page(@Valid KnowledgeBaseQueryRequest request) {
        return ApiResponse.success(knowledgeBaseService.pageKnowledgeBases(request));
    }
}
