package com.opsagent.common.mq;

/**
 * 文档解析完成后可靠触发 Embedding 与 Elasticsearch 索引的事件载荷。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record DocumentIndexRequested(
        long documentId,
        long taskId,
        int documentVersion,
        String chunkStrategyVersion) {
}
