package com.opsagent.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import javax.crypto.SecretKey;

/** 负责 JWT 的签发、签名验证和声明解析。 */
public class JwtService {
    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties p) {
        properties = p;
        byte[] bytes = p.getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32)
            throw new IllegalStateException("OPS_JWT_SECRET 必须至少包含 32 个 UTF-8 字节");
        key = Keys.hmacShaKeyFor(bytes);
    }

    public IssuedToken issue(long userId, String username, List<String> roles) {
        Instant now = Instant.now(), expires = now.plus(properties.getAccessTokenTtl());
        String id = UUID.randomUUID().toString();
        String token =
                Jwts.builder()
                        .id(id)
                        .subject(Long.toString(userId))
                        .claim("username", username)
                        .claim("roles", roles)
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(expires))
                        .signWith(key)
                        .compact();
        return new IssuedToken(token, id, expires);
    }

    public OpsPrincipal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        List<?> raw = c.get("roles", List.class);
        List<String> roles = raw == null ? List.of() : raw.stream().map(String::valueOf).toList();
        return new OpsPrincipal(
                Long.parseLong(c.getSubject()), c.get("username", String.class), c.getId(), roles);
    }
}
