package com.opsagent.rag;

import java.util.List;

/**
 * 定义可替换的 Query-Passage 相关性重排能力。
 *
 * @author heyu
 * @since 2026/9/3
 */
public interface RerankProvider {

    boolean available();

    List<RerankResult> rerank(String query, List<RerankDocument> candidates, int topN);
}
