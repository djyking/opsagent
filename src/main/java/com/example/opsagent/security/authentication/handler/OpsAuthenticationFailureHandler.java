package com.example.opsagent.security.authentication.handler;

import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.common.exception.ErrorCode;
import com.example.opsagent.security.authentication.exception.OpsLoginRequestException;
import com.example.opsagent.security.handler.OpsSecurityResponseWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 将登录参数错误和认证失败分别转换为 400 与 401 响应。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Component
@RequiredArgsConstructor
public class OpsAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final OpsSecurityResponseWriter responseWriter;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException, ServletException {
        if (exception instanceof OpsLoginRequestException) {
            responseWriter.write(
                    response,
                    HttpStatus.BAD_REQUEST.value(),
                    ApiResponse.fail(ErrorCode.BAD_REQUEST.getCode(), exception.getMessage()));
            return;
        }
        responseWriter.write(
                response,
                HttpStatus.UNAUTHORIZED.value(),
                ApiResponse.fail(ErrorCode.UNAUTHORIZED.getCode(), "用户名或密码错误"));
    }
}
