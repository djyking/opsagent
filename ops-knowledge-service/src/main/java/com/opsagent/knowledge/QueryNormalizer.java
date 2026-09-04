package com.opsagent.knowledge;

import org.springframework.stereotype.Component;

import java.text.Normalizer;

/**
 * 对检索问题执行 Unicode、空白和长度规范化，同时保留业务标识。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class QueryNormalizer {
    private static final int MAXIMUM_LENGTH = 2000;

    String normalize(String query) {
        String normalized = Normalizer.normalize(
                query == null ? "" : query, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ");
        if (normalized.length() > MAXIMUM_LENGTH) {
            return normalized.substring(0, MAXIMUM_LENGTH);
        }
        return normalized;
    }
}
