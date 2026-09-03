package com.example.opsagent.auth.dto;

/**
 * 登录返回token。
 *
 * @author heyu
 * @since 2026/7/23
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {}
