package com.opsagent.common.security;

import java.time.Instant;

/**
 * JWT 签发结果，包含原始令牌、唯一标识和过期时间。
 *
 * @author heyu
 * @since 2026/9/2
 */
public record IssuedToken(String token, String tokenId, Instant expiresAt) {}
