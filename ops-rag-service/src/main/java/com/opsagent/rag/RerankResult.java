package com.opsagent.rag;

/**
 * 保存 Rerank Provider 返回的候选序号、相关性分数和新排名。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record RerankResult(int candidateIndex, double score, int rank) {
}
