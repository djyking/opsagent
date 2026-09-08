package com.opsagent.common.core;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * 知识查询一次向量化及其最多三次网络尝试的保守额度，所有调用方共享同一合同。
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class QueryEmbeddingBudget {
    private static final int MAXIMUM_QUERY_LENGTH = 2000;
    private static final int MAXIMUM_ATTEMPTS = 3;

    private QueryEmbeddingBudget() {}

    public static int reserve(String query) {
        // Keep aligned with knowledge QueryNormalizer before any provider request.
        String normalized =
                Normalizer.normalize(query == null ? "" : query, Normalizer.Form.NFKC)
                        .trim()
                        .replaceAll("\\s+", " ");
        if (normalized.length() > MAXIMUM_QUERY_LENGTH) {
            normalized = normalized.substring(0, MAXIMUM_QUERY_LENGTH);
        }
        int bytes = "{\"input\":[\"\"]}".getBytes(StandardCharsets.UTF_8).length;
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            if (value == '"' || value == '\\') {
                bytes += 2;
            } else if (value < 0x20) {
                // Six bytes safely covers both short and Unicode JSON escapes.
                bytes += 6;
            } else if (Character.isHighSurrogate(value)
                    && index + 1 < normalized.length()
                    && Character.isLowSurrogate(normalized.charAt(index + 1))) {
                // Jackson's UTF-8 generator may serialize the pair as two Unicode escapes.
                bytes += 12;
                index++;
            } else if (Character.isSurrogate(value)) {
                bytes += 6;
            } else {
                bytes += value < 0x80 ? 1 : value < 0x800 ? 2 : 3;
            }
        }
        // Protocol/model metadata margin, not claimed as exact provider-billed usage.
        return MAXIMUM_ATTEMPTS * (bytes + 512);
    }
}
