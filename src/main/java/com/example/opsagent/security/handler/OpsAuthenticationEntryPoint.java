package com.example.opsagent.security.handler;

import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.common.exception.ErrorCode;
import com.example.opsagent.security.config.OpsSecurityProperties;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

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

    private final OpsSecurityProperties securityProperties;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException)
            throws IOException, ServletException {
        if (isBrowserNavigation(request)) {
            response.sendRedirect(securityProperties.getLoginPageUrl());
            return;
        }
        responseWriter.write(
                response,
                HttpStatus.UNAUTHORIZED.value(),
                ApiResponse.fail(ErrorCode.UNAUTHORIZED.getCode(), "未认证或认证凭证无效"));
    }

    private boolean isBrowserNavigation(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return HttpMethod.GET.matches(request.getMethod())
                && accept != null
                && accept.toLowerCase(java.util.Locale.ROOT).contains("text/html");
    }
}
