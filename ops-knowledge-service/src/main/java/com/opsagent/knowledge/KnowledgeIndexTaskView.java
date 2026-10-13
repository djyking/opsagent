package com.opsagent.knowledge;

import java.time.LocalDateTime;

/**
 * 管理端索引任务与当前源文档的可操作性快照。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record KnowledgeIndexTaskView(
        long id, long documentId, int documentVersion, Integer currentDocumentVersion,
        String operation, String status, int retryCount, String lastError,
        String documentName, String documentStatus, String reviewStatus,
        boolean documentDeleted, boolean repairable, String repairReason,
        LocalDateTime updateTime) {

    static String blockedReason(String operation, boolean exists, boolean deleted,
            int taskVersion, int currentVersion, String parseStatus, String reviewStatus,
            String indexStatus, long chunkCount) {
        if ("DELETE".equals(operation)) {
            return !exists || deleted || !"PUBLISHED".equals(reviewStatus)
                    ? "" : "文档目前已发布，不能重放旧的删除操作";
        }
        if (!"INDEX".equals(operation)) return "不支持的历史操作，请联系管理员核查";
        if (!exists) return "源文档已不存在，保留此任务供追溯，无需重新索引";
        if (deleted) return "源文档已删除，保留此任务供追溯，无需重新索引";
        if (taskVersion != currentVersion) return "任务属于旧版本；如当前版本仍需修复，请使用单文档修复";
        if (!"PUBLISHED".equals(reviewStatus)) return "文档尚未发布或已归档，请先完成知识审核发布";
        if (!"PARSED".equals(parseStatus) && !"INDEXED".equals(parseStatus)) return "文档尚未完成解析，请先在知识库重新解析";
        if (chunkCount == 0) return "文档没有有效切片，请先在知识库重新解析";
        if ("SUCCESS".equals(indexStatus)) return "当前文档索引已成功，此失败任务仅保留历史记录";
        return "";
    }
}
