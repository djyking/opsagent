package com.example.opsagent.security.authentication.handler;

import java.io.IOException;

import com.example.opsagent.auth.dto.LoginResponse;
import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.security.authentication.user.OpsUserPrincipal;
import com.example.opsagent.security.handler.OpsSecurityResponseWriter;
import com.example.opsagent.security.jwt.OpsTokenProperties;
import com.example.opsagent.security.jwt.OpsTokenService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 登录认证成功后生成 JWT 并返回统一响应。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Component
@RequiredArgsConstructor
public class OpsAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final OpsTokenService tokenService;

    private final OpsTokenProperties tokenProperties;

    private final OpsSecurityResponseWriter responseWriter;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
        Authentication authentication) throws IOException, ServletException {
        OpsUserPrincipal principal = (OpsUserPrincipal) authentication.getPrincipal();
        String token = tokenService.generateToken(principal);
        LoginResponse loginResponse = new LoginResponse(token, "Bearer", tokenProperties.getExpiresInSeconds());
        responseWriter.write(response, HttpStatus.OK.value(), ApiResponse.success(loginResponse));
    }
}
