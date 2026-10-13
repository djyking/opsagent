package com.opsagent.knowledge;

import java.util.List;

/**
 * 定义文档和查询必须共同使用的向量模型契约。
 *
 * @author heyu
 * @since 2026/8/30
 */
public interface EmbeddingClient {
    boolean configured();

    String model();

    int dimensions();

    EmbeddingBatchResult embedBatch(List<String> texts);

    default List<List<Double>> embed(List<String> texts) {
        return embedBatch(texts).vectors();
    }
}
