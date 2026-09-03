package com.example.opsagent.document.controller;

import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.document.service.DocumentService;
import com.example.opsagent.document.vo.DocumentChunkVO;
import com.example.opsagent.document.vo.DocumentVO;

import lombok.RequiredArgsConstructor;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 提供工单范围内的文档上传、解析和切片查询接口。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/tickets/{ticketId}/documents")
    public ApiResponse<DocumentVO> upload(
            @PathVariable Long ticketId, @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(documentService.upload(ticketId, file));
    }

    @GetMapping("/tickets/{ticketId}/documents")
    public ApiResponse<List<DocumentVO>> listByTicket(@PathVariable Long ticketId) {
        return ApiResponse.success(documentService.listByTicket(ticketId));
    }

    @GetMapping("/documents/{id}")
    public ApiResponse<DocumentVO> detail(@PathVariable Long id) {
        return ApiResponse.success(documentService.detail(id));
    }

    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<List<DocumentChunkVO>> chunks(@PathVariable Long id) {
        return ApiResponse.success(documentService.listChunks(id));
    }

    @PostMapping("/documents/{id}/parse")
    public ApiResponse<DocumentVO> parse(@PathVariable Long id) {
        return ApiResponse.success(documentService.parseDocument(id));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return ApiResponse.success();
    }
}
