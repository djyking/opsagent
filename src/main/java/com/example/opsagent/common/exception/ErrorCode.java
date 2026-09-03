package com.example.opsagent.common.exception;

/**
 * 定义通用业务错误码。
 *
 * @author heyu
 * @since 2026/8/15
 */
public enum ErrorCode implements IErrorCode {
    SUCCESS(0, "success"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证或认证凭证无效"),
    FORBIDDEN(403, "没有访问权限"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "业务状态冲突"),
    INTERNAL_ERROR(500, "系统内部错误");

    private final Integer code;

    private final String message;

    ErrorCode(Integer code, String message) {
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
