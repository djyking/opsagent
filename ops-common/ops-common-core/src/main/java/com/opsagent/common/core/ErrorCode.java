package com.opsagent.common.core;

/**
 * 跨服务共享的业务错误码。
 *
 * @author heyu
 * @since 2026/7/17
 */
public enum ErrorCode {
    VALIDATION(40000),
    UNAUTHENTICATED(40100),
    FORBIDDEN(40300),
    NOT_FOUND(40400),
    CONFLICT(40900),
    SYSTEM_ERROR(50000),
    MIDDLEWARE_UNAVAILABLE(50300);
    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
