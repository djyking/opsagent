package com.example.opsagent.common.exception;

import lombok.Getter;

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
