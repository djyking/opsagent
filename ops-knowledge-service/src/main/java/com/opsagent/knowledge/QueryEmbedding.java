package com.opsagent.knowledge;

import java.util.List;

/**
 * Reuses only one request's normalized query vector, never document results or another actor's
 * data.
 *
 * @author heyu
 * @since 2026/9/3
 */
final class QueryEmbedding {
    private final EmbeddingClient client;
    private final String query;
    private EmbeddingBatchResult result;
    private RuntimeException failure;
    private boolean attempted;

    QueryEmbedding(EmbeddingClient client, String query) {
        this.client = client;
        this.query = new QueryNormalizer().normalize(query);
    }

    synchronized EmbeddingBatchResult get(String requestedQuery) {
        if (!query.equals(new QueryNormalizer().normalize(requestedQuery)))
            throw new IllegalArgumentException("查询向量仅可在同一次相同问题中复用");
        if (!attempted) {
            attempted = true;
            try {
                result = client.embedBatch(List.of(query));
                if (result == null
                        || result.vectors().size() != 1
                        || result.vectors().get(0).isEmpty())
                    throw new IllegalStateException("查询向量结果无效");
            } catch (RuntimeException exception) {
                failure = exception;
            }
        }
        if (failure != null) throw failure;
        return result;
    }
}
