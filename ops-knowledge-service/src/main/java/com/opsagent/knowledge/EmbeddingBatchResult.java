package com.opsagent.knowledge;

import java.util.List;

/**
 * 保存一次批量 Embedding 调用的向量、模型、维度和 Provider Token 用量。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record EmbeddingBatchResult(
        List<List<Double>> vectors,
        String model,
        int dimensions,
        int tokenUsage) {

    public EmbeddingBatchResult {
        vectors = vectors.stream().map(List::copyOf).toList();
    }
}
