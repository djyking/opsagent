package com.opsagent.auth;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

/** 认证服务内部使用的请求与响应模型集合。 */
final class AuthDtos {
    private AuthDtos() {}

    record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    record RefreshRequest(@NotBlank String refreshToken) {}

    record TokenResponse(
            String accessToken, String refreshToken, String tokenType, Instant expiresAt) {}

    record CurrentUser(
            long userId, String username, List<String> roles, List<String> permissions) {}
}
