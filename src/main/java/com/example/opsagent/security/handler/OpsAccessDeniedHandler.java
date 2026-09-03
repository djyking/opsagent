package com.example.opsagent.security.handler;

import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.common.exception.ErrorCode;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 处理已认证用户权限不足的 403 响应。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Component
@RequiredArgsConstructor
public class OpsAccessDeniedHandler implements AccessDeniedHandler {

    private final OpsSecurityResponseWriter responseWriter;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        responseWriter.write(
                response,
                HttpStatus.FORBIDDEN.value(),
                ApiResponse.fail(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMessage()));
    }
}
