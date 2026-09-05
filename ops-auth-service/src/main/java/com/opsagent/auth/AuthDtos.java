package com.opsagent.auth;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

/**
 * 认证服务内部使用的请求与响应模型集合。
 *
 * @author heyu
 * @since 2026/8/1
 */
final class AuthDtos {
    private AuthDtos() {}

    /**
     * 用户登录请求参数。
     *
     * @author heyu
     * @since 2026/8/1
     */
    record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    /**
     * 公开注册只接受账号资料，不接受角色或权限字段。
     * @author heyu
     * @since 2026/9/3
     */
    record RegisterRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 6, max = 72) String password,
            @Size(max = 64) String displayName) {}

    /**
     * 刷新访问令牌的请求参数。
     *
     * @author heyu
     * @since 2026/8/1
     */
    record RefreshRequest(@NotBlank String refreshToken) {}

    /**
     * 认证令牌响应数据。
     *
     * @author heyu
     * @since 2026/8/1
     */
    record TokenResponse(
            String accessToken, String refreshToken, String tokenType, Instant expiresAt) {}

    /**
     * 当前登录用户响应数据。
     *
     * @author heyu
     * @since 2026/8/1
     */
    record CurrentUser(
            long userId, String username, List<String> roles, List<String> permissions) {}
}
