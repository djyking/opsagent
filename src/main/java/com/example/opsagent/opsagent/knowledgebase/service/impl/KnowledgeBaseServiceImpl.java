package com.example.opsagent.opsagent.knowledgebase.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.opsagent.common.BusinessException;
import com.example.opsagent.opsagent.common.ErrorCode;
import com.example.opsagent.opsagent.common.PageResponse;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseCreateRequest;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseQueryRequest;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseResponse;
import com.example.opsagent.opsagent.knowledgebase.dto.KnowledgeBaseUpdateRequest;
import com.example.opsagent.opsagent.knowledgebase.entity.KnowledgeBase;
import com.example.opsagent.opsagent.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.opsagent.opsagent.knowledgebase.service.KnowledgeBaseService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> implements KnowledgeBaseService {

    @Override
    public KnowledgeBaseResponse createKnowledgeBase(KnowledgeBaseCreateRequest request) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        BeanUtils.copyProperties(request, knowledgeBase);
        save(knowledgeBase);
        return toResponse(knowledgeBase);
    }

    @Override
    public KnowledgeBaseResponse updateKnowledgeBase(KnowledgeBaseUpdateRequest request) {
        KnowledgeBase existing = getById(request.getId());
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found");
        }
        BeanUtils.copyProperties(request, existing);
        updateById(existing);
        return toResponse(getById(request.getId()));
    }

    @Override
    public void deleteKnowledgeBase(Long id) {
        if (getById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found");
        }
        removeById(id);
    }

    @Override
    public KnowledgeBaseResponse getKnowledgeBase(Long id) {
        KnowledgeBase knowledgeBase = getById(id);
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found");
        }
        return toResponse(knowledgeBase);
    }

    @Override
    public PageResponse<KnowledgeBaseResponse> pageKnowledgeBases(KnowledgeBaseQueryRequest request) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .like(StringUtils.hasText(request.getName()), KnowledgeBase::getName, request.getName())
                .eq(StringUtils.hasText(request.getOwner()), KnowledgeBase::getOwner, request.getOwner())
                .orderByDesc(KnowledgeBase::getCreatedAt);
        Page<KnowledgeBase> page = page(new Page<>(request.getPageNo(), request.getPageSize()), wrapper);
        List<KnowledgeBaseResponse> records = page.getRecords().stream().map(this::toResponse).toList();
        return PageResponse.of(page, records);
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        KnowledgeBaseResponse response = new KnowledgeBaseResponse();
        BeanUtils.copyProperties(knowledgeBase, response);
        return response;
    }
}
