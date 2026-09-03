package com.opsagent.common.core;

/**
 * 可预期的业务异常，由全局异常处理器转换为统一响应。
 *
 * @author heyu
 * @since 2026/7/16
 */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
