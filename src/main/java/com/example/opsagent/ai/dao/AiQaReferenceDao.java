package com.example.opsagent.ai.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsagent.ai.entity.AiQaReference;

import org.apache.ibatis.annotations.Mapper;

/**
 * 持久化 AI 问答引用切片。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Mapper
public interface AiQaReferenceDao extends BaseMapper<AiQaReference> {}
