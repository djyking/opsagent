package com.opsagent.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 控制接口仅验证独立固定凭据，不接收用户JWT和任意目标地址。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class DemoControlFilter extends OncePerRequestFilter {
    private final byte[] token;

    DemoControlFilter(@Value("${ops.demo.control-token:}") String value) {
        if (value.length() < 32)
            throw new IllegalArgumentException("OPS_DEMO_CONTROL_TOKEN_TOO_SHORT");
        token = value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/internal/")) {
            String actual = request.getHeader("X-Demo-Control-Token");
            if (actual == null
                    || !MessageDigest.isEqual(token, actual.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(403);
                response.setContentType("application/json");
                response.getWriter().write("{\"reasonCode\":\"FORBIDDEN\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
