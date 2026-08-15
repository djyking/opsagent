package com.example.opsagent.security.handler;

import java.io.IOException;

import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.common.exception.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 处理未提供有效认证身份的 401 响应。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Component
@RequiredArgsConstructor
public class OpsAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final OpsSecurityResponseWriter responseWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
        AuthenticationException authenticationException) throws IOException, ServletException {
        responseWriter.write(response, HttpStatus.UNAUTHORIZED.value(),
            ApiResponse.fail(ErrorCode.UNAUTHORIZED.getCode(), "未认证或认证凭证无效"));
    }
}
