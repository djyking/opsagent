package com.opsagent.rag;

import java.util.Map;

/**
 * 保存经知识服务权限过滤后返回的真实检索片段及其来源元数据。
 *
 * @author heyu
 * @since 2026/8/30
 */
public record RetrievedChunk(
        long chunkId,
        long documentId,
        int chunkIndex,
        String content,
        String documentName,
        Integer page,
        Integer version,
        String updateTime,
        double score) {
    static RetrievedChunk from(Map<String, Object> row) {
        return new RetrievedChunk(
                number(row, "chunkId", "chunkid").longValue(),
                number(row, "documentId", "documentid").longValue(),
                number(row, "chunkIndex", "chunkindex").intValue(),
                text(row, "content"),
                text(row, "documentName", "documentname"),
                nullableInteger(row, "page", "pageNumber", "pagenumber"),
                nullableInteger(row, "version"),
                text(row, "updateTime", "updatetime"),
                nullableNumber(row, "score") == null
                        ? 0.0D
                        : nullableNumber(row, "score").doubleValue());
    }

    private static Number number(Map<String, Object> row, String... keys) {
        Number value = nullableNumber(row, keys);
        return value == null ? 0L : value;
    }

    private static Number nullableNumber(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value instanceof Number number) {
                return number;
            }
            if (value != null && !value.toString().isBlank()) {
                return Double.parseDouble(value.toString());
            }
        }
        return null;
    }

    private static Integer nullableInteger(Map<String, Object> row, String... keys) {
        Number value = nullableNumber(row, keys);
        return value == null ? null : value.intValue();
    }

    private static String text(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }
}
