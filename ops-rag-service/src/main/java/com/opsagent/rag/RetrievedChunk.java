package com.opsagent.rag;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 保存权限过滤后的切片、引用定位信息和各检索阶段分数。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record RetrievedChunk(
        long chunkId,
        long documentId,
        int chunkIndex,
        String content,
        String documentName,
        String headingPath,
        Integer pageStart,
        Integer pageEnd,
        Integer version,
        String updateTime,
        Double bm25Score,
        Double vectorScore,
        Double rrfScore,
        Double rerankScore,
        Set<String> channels,
        String retrievalMode,
        String degradedReason) {

    RetrievedChunk(
            long chunkId,
            long documentId,
            int chunkIndex,
            String content,
            String documentName,
            Integer page,
            Integer version,
            String updateTime,
            double score) {
        this(
                chunkId,
                documentId,
                chunkIndex,
                content,
                documentName,
                "",
                page,
                page,
                version,
                updateTime,
                null,
                score,
                null,
                null,
                Set.of("VECTOR"),
                "VECTOR",
                "");
    }

    static RetrievedChunk from(Map<String, Object> row) {
        Double legacyScore = decimal(row, "score");
        return new RetrievedChunk(
                number(row, "chunkId", "chunkid").longValue(),
                number(row, "documentId", "documentid").longValue(),
                number(row, "chunkIndex", "chunkindex").intValue(),
                text(row, "content"),
                text(row, "documentName", "documentname"),
                text(row, "headingPath", "headingpath"),
                nullableInteger(row, "pageStart", "pagestart", "page", "pageNumber"),
                nullableInteger(row, "pageEnd", "pageend", "page", "pageNumber"),
                nullableInteger(row, "version"),
                text(row, "updateTime", "updatetime"),
                decimal(row, "bm25Score", "bm25score"),
                decimal(row, "vectorScore", "vectorscore", "score"),
                decimal(row, "rrfScore", "rrfscore"),
                decimal(row, "rerankScore", "rerankscore"),
                stringSet(row.get("channels")),
                textOr(
                        row,
                        new String[]{"retrievalMode", "retrievalmode"},
                        legacyScore == null ? "" : "VECTOR"),
                text(row, "degradedReason", "degradedreason"));
    }

    RetrievedChunk withRerankScore(Double score) {
        return new RetrievedChunk(
                chunkId, documentId, chunkIndex, content, documentName, headingPath,
                pageStart, pageEnd, version, updateTime, bm25Score, vectorScore,
                rrfScore, score, channels, retrievalMode, degradedReason);
    }

    Integer page() {
        return pageStart;
    }

    double score() {
        if (rerankScore != null) {
            return rerankScore;
        }
        if (rrfScore != null) {
            return rrfScore;
        }
        if (vectorScore != null) {
            return vectorScore;
        }
        return bm25Score == null ? 0.0D : bm25Score;
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

    private static Double decimal(Map<String, Object> row, String... keys) {
        Number value = nullableNumber(row, keys);
        return value == null ? null : value.doubleValue();
    }

    private static String text(Map<String, Object> row, String... keys) {
        return textOr(row, keys, "");
    }

    private static String textOr(Map<String, Object> row, String[] keys, String fallback) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return fallback;
    }

    private static Set<String> stringSet(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> result.add(item.toString()));
        }
        return Set.copyOf(result);
    }
}
