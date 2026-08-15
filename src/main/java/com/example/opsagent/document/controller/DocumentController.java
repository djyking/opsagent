package com.example.opsagent.document.controller;

import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.document.dto.DocumentQueryRequest;
import com.example.opsagent.document.service.DocumentService;
import com.example.opsagent.document.vo.DocumentChunkVO;
import com.example.opsagent.document.vo.DocumentVO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public ApiResponse<DocumentVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("uploader") String uploader) {
        return ApiResponse.success(documentService.upload(file, uploader));
    }

    @GetMapping
    public ApiResponse<PageResponse<DocumentVO>> page(@Valid @ModelAttribute DocumentQueryRequest request) {
        return ApiResponse.success(documentService.pageDocuments(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentVO> detail(@PathVariable Long id) {
        return ApiResponse.success(documentService.detail(id));
    }

    @GetMapping("/{id}/chunks")
    public ApiResponse<List<DocumentChunkVO>> chunks(@PathVariable Long id) {
        return ApiResponse.success(documentService.listChunks(id));
    }

    @PostMapping("/{id}/parse")
    public ApiResponse<DocumentVO> parse(@PathVariable Long id) {
        return ApiResponse.success(documentService.parseDocument(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return ApiResponse.success();
    }
}
