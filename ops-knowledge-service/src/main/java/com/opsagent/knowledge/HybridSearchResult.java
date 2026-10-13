package com.opsagent.knowledge;

import java.util.List;
import java.util.Map;

/**
 * 汇总 Hybrid Search 结果、降级状态、分阶段排序和耗时。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record HybridSearchResult(
        String query,
        Map<String, Object> filters,
        List<RetrievedChunk> candidates,
        List<Long> bm25Rank,
        List<Long> vectorRank,
        List<Long> rrfRank,
        Map<String, Long> durationMillis,
        String retrievalMode,
        String degradedReason) {
}
