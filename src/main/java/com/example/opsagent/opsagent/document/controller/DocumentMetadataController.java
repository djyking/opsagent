package com.example.opsagent.opsagent.document.controller;

import com.example.opsagent.opsagent.common.ApiResponse;
import com.example.opsagent.opsagent.common.PageResponse;
import com.example.opsagent.opsagent.document.dto.DocumentCreateRequest;
import com.example.opsagent.opsagent.document.dto.DocumentQueryRequest;
import com.example.opsagent.opsagent.document.dto.DocumentResponse;
import com.example.opsagent.opsagent.document.dto.DocumentUpdateRequest;
import com.example.opsagent.opsagent.document.service.DocumentMetadataService;
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

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/documents")
public class DocumentMetadataController {

    private final DocumentMetadataService documentMetadataService;

    @PostMapping
    public ApiResponse<DocumentResponse> create(@Valid @RequestBody DocumentCreateRequest request) {
        return ApiResponse.success(documentMetadataService.createDocument(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<DocumentResponse> update(@PathVariable Long id, @Valid @RequestBody DocumentUpdateRequest request) {
        request.setId(id);
        return ApiResponse.success(documentMetadataService.updateDocument(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @NotNull Long id) {
        documentMetadataService.deleteDocument(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse> detail(@PathVariable @NotNull Long id) {
        return ApiResponse.success(documentMetadataService.getDocument(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<DocumentResponse>> page(@Valid DocumentQueryRequest request) {
        return ApiResponse.success(documentMetadataService.pageDocuments(request));
    }

    @GetMapping("/by-knowledge-base/{knowledgeBaseId}")
    public ApiResponse<List<DocumentResponse>> listByKnowledgeBaseId(@PathVariable @NotNull Long knowledgeBaseId) {
        return ApiResponse.success(documentMetadataService.listByKnowledgeBaseId(knowledgeBaseId));
    }
}
