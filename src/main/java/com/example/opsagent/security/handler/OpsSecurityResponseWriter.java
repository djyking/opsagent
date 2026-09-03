package com.example.opsagent.security.handler;

import com.example.opsagent.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 将 Spring Security 异常统一写为项目标准 JSON 响应。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Component
@RequiredArgsConstructor
public class OpsSecurityResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, int status, ApiResponse<?> body)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
