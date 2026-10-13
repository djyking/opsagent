package com.opsagent.knowledge;

import java.util.Set;

/**
 * 保留双路召回分数、融合分数和引用定位信息的知识切片。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record RetrievedChunk(
        long chunkId,
        long documentId,
        int chunkIndex,
        String documentName,
        String headingPath,
        String content,
        Integer pageStart,
        Integer pageEnd,
        Double bm25Score,
        Double vectorScore,
        Double rrfScore,
        Double rerankScore,
        Set<RetrievalChannel> channels,
        String retrievalMode,
        String degradedReason) {
}
