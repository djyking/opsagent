package com.example.opsagent.opsagent.knowledgebase.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.opsagent.common.PageResponse;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseCreateRequest;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseQueryRequest;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseResponse;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseUpdateRequest;
import com.example.opsagent.opsagent.knowledgebase.entity.KnowledgeBase;

public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    KnowledgeBaseResponse createKnowledgeBase(KnowledgeBaseCreateRequest request);

    KnowledgeBaseResponse updateKnowledgeBase(KnowledgeBaseUpdateRequest request);

    void deleteKnowledgeBase(Long id);

    KnowledgeBaseResponse getKnowledgeBase(Long id);

    PageResponse<KnowledgeBaseResponse> pageKnowledgeBases(KnowledgeBaseQueryRequest request);
}
