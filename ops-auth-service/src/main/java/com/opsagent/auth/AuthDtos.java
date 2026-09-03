package com.opsagent.auth;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

/**
 * 认证服务内部使用的请求与响应模型集合。
 *
 * @author heyu
 * @since 2026/9/2
 */
final class AuthDtos {
    private AuthDtos() {}

    /**
     * 用户登录请求参数。
     *
     * @author heyu
     * @since 2026/9/2
     */
    record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    /**
     * 刷新访问令牌的请求参数。
     *
     * @author heyu
     * @since 2026/9/2
     */
    record RefreshRequest(@NotBlank String refreshToken) {}

    /**
     * 认证令牌响应数据。
     *
     * @author heyu
     * @since 2026/9/2
     */
    record TokenResponse(
            String accessToken, String refreshToken, String tokenType, Instant expiresAt) {}

    /**
     * 当前登录用户响应数据。
     *
     * @author heyu
     * @since 2026/9/2
     */
    record CurrentUser(
            long userId, String username, List<String> roles, List<String> permissions) {}
}
