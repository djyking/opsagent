package com.opsagent.platform;

/**
 * 用户可见的观测信息仅保留摘要，地址不携带凭据或查询串。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class ObservabilitySanitizer {
    private ObservabilitySanitizer() {}

    static String endpoint(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).replaceAll("[\\r\\n]", "");
        text = text.replaceAll("(?i)(//)[^/@]+@", "$1******@");
        int query = text.indexOf('?');
        if (query >= 0) text = text.substring(0, query);
        int fragment = text.indexOf('#');
        if (fragment >= 0) text = text.substring(0, fragment);
        if (text.contains("@") && !text.contains("://")) return "******";
        return text;
    }

    static String summary(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(password|passwd|secret|token|api[-_]?key)\\s*[:=]\\s*[^\\s,;]+",
                "$1=******").replaceAll("(?i)(https?://)[^/@\\s]+@", "$1******@");
        return safe.length() > 500 ? safe.substring(0, 500) : safe;
    }
}
