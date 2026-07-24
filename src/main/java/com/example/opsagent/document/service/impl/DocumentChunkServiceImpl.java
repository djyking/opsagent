package com.example.opsagent.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.document.entity.DocumentChunk;
import com.example.opsagent.document.dao.DocumentChunkDao;
import com.example.opsagent.document.service.DocumentChunkService;
import org.springframework.stereotype.Service;

@Service
public class DocumentChunkServiceImpl extends ServiceImpl<DocumentChunkDao, DocumentChunk>
        implements DocumentChunkService {
}
