package com.opsagent.common.core;

import org.slf4j.MDC;

/**
 * 统一 API 响应结构，携带业务状态、数据和当前请求的链路标识。
 *
 * @author heyu
 * @since 2026/9/2
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data, MDC.get("traceId"));
    }

    public static ApiResponse<Void> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> failure(int code, String message) {
        return new ApiResponse<>(code, message, null, MDC.get("traceId"));
    }
}
