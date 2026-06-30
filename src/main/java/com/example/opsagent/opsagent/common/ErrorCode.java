package com.example.opsagent.opsagent.common;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(0, "success"),
    PARAM_ERROR(400, "request parameter error"),
    NOT_FOUND(404, "resource not found"),
    BUSINESS_ERROR(5000, "business error"),
    SYSTEM_ERROR(500, "system error");

    private final Integer code;

    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
