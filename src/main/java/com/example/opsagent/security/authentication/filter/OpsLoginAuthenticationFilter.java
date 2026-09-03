package com.example.opsagent.security.authentication.filter;

import com.example.opsagent.auth.dto.LoginRequest;
import com.example.opsagent.security.authentication.exception.OpsLoginRequestException;
import com.example.opsagent.security.authentication.token.OpsAuthenticationToken;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * 解析 JSON 登录请求并将未认证 Token 交给认证管理器。
 *
 * @author heyu
 * @since 2026/8/15
 */
public class OpsLoginAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    public static final String LOGIN_PATH = "/api/auth/login";

    private final ObjectMapper objectMapper;

    public OpsLoginAuthenticationFilter(
            AuthenticationManager authenticationManager, ObjectMapper objectMapper) {
        super(
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, LOGIN_PATH),
                authenticationManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public Authentication attemptAuthentication(
            HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        LoginRequest loginRequest;
        try {
            loginRequest = objectMapper.readValue(request.getInputStream(), LoginRequest.class);
        } catch (JacksonException exception) {
            throw new OpsLoginRequestException("登录请求 JSON 格式错误", exception);
        } catch (IOException exception) {
            throw new OpsLoginRequestException("无法读取登录请求", exception);
        }
        if (!StringUtils.hasText(loginRequest.getUsername())
                || !StringUtils.hasText(loginRequest.getPassword())) {
            throw new OpsLoginRequestException("username 和 password 不能为空");
        }
        if (loginRequest.getUsername().trim().length() > 64
                || loginRequest
                                .getPassword()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                .length
                        > 72) {
            throw new OpsLoginRequestException("username 或 password 长度不合法");
        }

        OpsAuthenticationToken token =
                OpsAuthenticationToken.unauthenticated(
                        loginRequest.getUsername().trim(), loginRequest.getPassword());
        token.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return getAuthenticationManager().authenticate(token);
    }
}
