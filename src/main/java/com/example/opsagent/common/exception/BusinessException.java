package com.example.opsagent.common.exception;

import lombok.Getter;

/**
 * 封装可预期业务错误的业务异常。
 *
 * @author heyu
 * @since 2026/7/22
 */
@Getter
public class BusinessException extends RuntimeException {

    private final IErrorCode errorCode;

    public BusinessException(IErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(IErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
