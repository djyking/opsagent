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
    private final String diagnosticCode;
    private final boolean responseStarted;

    AiProviderException(String provider, int statusCode, String message, Throwable cause) {
        this(provider, statusCode, message, cause, FailureKind.UNKNOWN);
    }

    AiProviderException(
            String provider, int statusCode, String message, Throwable cause, FailureKind kind) {
        this(provider, statusCode, message, cause, kind, kind.name(), false);
    }

    AiProviderException(
            String provider,
            int statusCode,
            String message,
            Throwable cause,
            FailureKind kind,
            String diagnosticCode,
            boolean responseStarted) {
        super(message, cause);
        this.provider = provider;
        this.statusCode = statusCode;
        this.kind = kind;
        this.diagnosticCode = diagnosticCode;
        this.responseStarted = responseStarted;
    }

    String provider() {
        return provider;
    }

    int statusCode() {
        return statusCode;
    }

    FailureKind kind() {
        return kind;
    }

    String diagnosticCode() {
        return diagnosticCode;
    }

    boolean responseStarted() {
        return responseStarted;
    }

    /**
     * @author heyu
     */
    enum FailureKind {
        HTTP,
        NETWORK,
        TIMEOUT,
        PROTOCOL,
        CANCELLED,
        BUDGET,
        UNKNOWN
    }
}
