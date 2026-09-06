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
    private final FailureKind kind;

    AiProviderException(String provider, int statusCode, String message, Throwable cause) {
        this(provider, statusCode, message, cause, FailureKind.UNKNOWN);
    }

    AiProviderException(String provider, int statusCode, String message, Throwable cause, FailureKind kind) {
        super(message, cause);
        this.provider = provider;
        this.statusCode = statusCode;
        this.kind = kind;
    }

    String provider() {
        return provider;
    }

    int statusCode() {
        return statusCode;
    }

    FailureKind kind() { return kind; }

    /** @author heyu */
    enum FailureKind { HTTP, NETWORK, TIMEOUT, PROTOCOL, CANCELLED, UNKNOWN }
}
