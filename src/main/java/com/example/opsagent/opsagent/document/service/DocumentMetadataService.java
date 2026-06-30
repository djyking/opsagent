package com.example.opsagent.opsagent.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.opsagent.common.PageResponse;
import com.example.opsagent.opsagent.document.dto.DocumentCreateRequest;
import com.example.opsagent.opsagent.document.dto.DocumentQueryRequest;
import com.example.opsagent.opsagent.document.dto.DocumentResponse;
import com.example.opsagent.opsagent.document.dto.DocumentUpdateRequest;
import com.example.opsagent.opsagent.document.entity.DocumentMetadata;

import java.util.List;

public interface DocumentMetadataService extends IService<DocumentMetadata> {

    DocumentResponse createDocument(DocumentCreateRequest request);

    DocumentResponse updateDocument(DocumentUpdateRequest request);

    void deleteDocument(Long id);

    DocumentResponse getDocument(Long id);

    PageResponse<DocumentResponse> pageDocuments(DocumentQueryRequest request);

    List<DocumentResponse> listByKnowledgeBaseId(Long knowledgeBaseId);
}
