package com.opsagent.knowledge;

import org.springframework.stereotype.Component;

/**
 * 使用稳定的中英文混合规则近似统计 Token，供 Java 17 环境下的切片预算使用。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class ApproxTokenCounter implements TokenCounter {

    @Override
    public int count(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int tokens = 0;
        int latinLength = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isLatinWord(codePoint)) {
                latinLength++;
                continue;
            }
            tokens += latinTokens(latinLength);
            latinLength = 0;
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            tokens++;
        }
        return Math.max(1, tokens + latinTokens(latinLength));
    }

    private boolean isLatinWord(int codePoint) {
        return codePoint < 128 && (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-');
    }

    private int latinTokens(int length) {
        return length == 0 ? 0 : Math.max(1, (length + 3) / 4);
    }
}
