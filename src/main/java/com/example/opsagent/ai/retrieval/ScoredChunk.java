package com.example.opsagent.ai.retrieval;

import com.example.opsagent.document.entity.DocumentChunk;

/**
 * 承载文档切片及其简单关键词相关性分数。
 *
 * @author heyu
 * @since 2026/8/16
 */
public record ScoredChunk(DocumentChunk chunk, double score) {}
