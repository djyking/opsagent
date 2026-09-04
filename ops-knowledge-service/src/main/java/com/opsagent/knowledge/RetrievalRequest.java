package com.opsagent.knowledge;

import java.util.Set;

/**
 * 描述一次带知识范围和文档范围约束的检索请求。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record RetrievalRequest(
        String query,
        Long knowledgeBaseId,
        Long documentId,
        String ticketId,
        String serviceId,
        Set<Long> allowedKnowledgeBaseIds,
        boolean administratorPreview,
        long userId,
        boolean administrator,
        int resultSize) {
}
