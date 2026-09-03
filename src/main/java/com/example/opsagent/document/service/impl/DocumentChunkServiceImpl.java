package com.example.opsagent.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.document.dao.DocumentChunkDao;
import com.example.opsagent.document.entity.DocumentChunk;
import com.example.opsagent.document.service.DocumentChunkService;

import org.springframework.stereotype.Service;

/**
 * 文档切片业务服务实现。
 *
 * @author heyu
 * @since 2026/7/16
 */
@Service
public class DocumentChunkServiceImpl extends ServiceImpl<DocumentChunkDao, DocumentChunk>
        implements DocumentChunkService {}
