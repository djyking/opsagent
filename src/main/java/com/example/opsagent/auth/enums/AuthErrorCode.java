package com.example.opsagent.auth.enums;

import com.example.opsagent.common.exception.IErrorCode;

/**
 * 登录错误枚举。
 *
 * @author heyu
 * @since 2026/7/19
 */
public enum AuthErrorCode implements IErrorCode {
    USER_NOT_EXIST(10001, "用户不存在"),
    USER_ALREADY_EXIST(10002, "用户已存在"),
    PASSWORD_ERROR(10003, "密码错误");

    private final Integer code;

    private final String message;

    AuthErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
