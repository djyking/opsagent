package com.opsagent.rag;

/**
 * 保存已脱敏的大模型调用失败分类，供统一错误映射和审计使用。
 *
 * @author heyu
 * @since 2026/8/30
 */
public class AiProviderException extends RuntimeException {
    private final String provider;
    private final int statusCode;

    AiProviderException(String provider, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.statusCode = statusCode;
    }

    String provider() {
        return provider;
    }

    int statusCode() {
        return statusCode;
    }
}
