package com.example.opsagent.opsagent.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.opsagent.common.BusinessException;
import com.example.opsagent.opsagent.common.ErrorCode;
import com.example.opsagent.opsagent.common.PageResponse;
import com.example.opsagent.opsagent.document.dto.DocumentCreateRequest;
import com.example.opsagent.opsagent.document.dto.DocumentQueryRequest;
import com.example.opsagent.opsagent.document.dto.DocumentResponse;
import com.example.opsagent.opsagent.document.dto.DocumentUpdateRequest;
import com.example.opsagent.opsagent.document.entity.DocumentMetadata;
import com.example.opsagent.opsagent.document.mapper.DocumentMetadataMapper;
import com.example.opsagent.opsagent.document.service.DocumentMetadataService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class DocumentMetadataServiceImpl extends ServiceImpl<DocumentMetadataMapper, DocumentMetadata> implements DocumentMetadataService {

    @Override
    public DocumentResponse createDocument(DocumentCreateRequest request) {
        DocumentMetadata document = new DocumentMetadata();
        BeanUtils.copyProperties(request, document);
        save(document);
        return toResponse(document);
    }

    @Override
    public DocumentResponse updateDocument(DocumentUpdateRequest request) {
        DocumentMetadata existing = getById(request.getId());
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        BeanUtils.copyProperties(request, existing);
        updateById(existing);
        return toResponse(getById(request.getId()));
    }

    @Override
    public void deleteDocument(Long id) {
        if (getById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        removeById(id);
    }

    @Override
    public DocumentResponse getDocument(Long id) {
        DocumentMetadata document = getById(id);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        return toResponse(document);
    }

    @Override
    public PageResponse<DocumentResponse> pageDocuments(DocumentQueryRequest request) {
        LambdaQueryWrapper<DocumentMetadata> wrapper = baseQuery(request)
                .orderByDesc(DocumentMetadata::getCreatedAt);
        Page<DocumentMetadata> page = page(new Page<>(request.getPageNo(), request.getPageSize()), wrapper);
        List<DocumentResponse> records = page.getRecords().stream().map(this::toResponse).toList();
        return PageResponse.of(page, records);
    }

    @Override
    public List<DocumentResponse> listByKnowledgeBaseId(Long knowledgeBaseId) {
        return list(new LambdaQueryWrapper<DocumentMetadata>()
                .eq(DocumentMetadata::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(DocumentMetadata::getCreatedAt))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private LambdaQueryWrapper<DocumentMetadata> baseQuery(DocumentQueryRequest request) {
        return new LambdaQueryWrapper<DocumentMetadata>()
                .eq(request.getKnowledgeBaseId() != null, DocumentMetadata::getKnowledgeBaseId, request.getKnowledgeBaseId())
                .like(StringUtils.hasText(request.getFileName()), DocumentMetadata::getFileName, request.getFileName())
                .eq(StringUtils.hasText(request.getFileType()), DocumentMetadata::getFileType, request.getFileType())
                .eq(StringUtils.hasText(request.getParseStatus()), DocumentMetadata::getParseStatus, request.getParseStatus());
    }

    private DocumentResponse toResponse(DocumentMetadata document) {
        DocumentResponse response = new DocumentResponse();
        BeanUtils.copyProperties(document, response);
        return response;
    }
}
