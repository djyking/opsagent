package com.example.opsagent.security.authentication.exception;

import org.springframework.security.authentication.AuthenticationServiceException;

/**
 * 表示登录 HTTP 请求体无法转换为有效的认证请求。
 *
 * @author heyu
 * @since 2026/8/16
 */
public class OpsLoginRequestException extends AuthenticationServiceException {

    public OpsLoginRequestException(String message) {
        super(message);
    }

    public OpsLoginRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
