package com.example.opsagent.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.ai.dao.AiQaReferenceDao;
import com.example.opsagent.ai.entity.AiQaReference;
import com.example.opsagent.ai.service.AiQaReferenceService;

import org.springframework.stereotype.Service;

/**
 * 实现 AI 问答引用切片的持久化。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Service
public class AiQaReferenceServiceImpl extends ServiceImpl<AiQaReferenceDao, AiQaReference>
        implements AiQaReferenceService {}
