/*
 * <p>文件名称: AuthErrorCode.java</p>
 * <p>项目描述: KOCA 金证云原生平台</p>
 * <p>公司名称: 深圳市金证科技股份有限公司</p>
 * <p>版权所有: (C) 2019-2023</p>
 */

package com.example.opsagent.auth.enums;

import com.example.opsagent.common.exception.IErrorCode;

/**
 * 登录错误枚举
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
