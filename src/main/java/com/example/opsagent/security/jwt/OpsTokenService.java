package com.example.opsagent.security.jwt;

import com.example.opsagent.security.authentication.user.OpsUserPrincipal;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.crypto.SecretKey;

/**
 * 负责 JWT 的签发、签名校验和声明解析。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Service
@RequiredArgsConstructor
public class OpsTokenService {

    private static final String USERNAME_CLAIM = "username";

    private final OpsTokenProperties properties;

    @PostConstruct
    public void validateConfiguration() {
        signingKey();
        if (properties.getExpireMinutes() <= 0) {
            throw new IllegalStateException("JWT 有效期 expire-minutes 必须大于 0");
        }
    }

    public String generateToken(OpsUserPrincipal principal) {
        Instant issuedAt = Instant.now();
        Instant expiration = issuedAt.plus(properties.getExpireMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(String.valueOf(principal.getUserId()))
                .claim(USERNAME_CLAIM, principal.getUsername())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration))
                .signWith(signingKey())
                .compact();
    }

    public Claims extractTokenClaims(String token) {
        return Jwts.parser().verifyWith(signingKey()).build().parseSignedClaims(token).getPayload();
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = extractTokenClaims(token);
            return claims.getExpiration() != null && claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean isExpired(String token) {
        try {
            Date expiration = extractTokenClaims(token).getExpiration();
            return expiration == null || !expiration.after(new Date());
        } catch (JwtException | IllegalArgumentException exception) {
            return true;
        }
    }

    public Long extractUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public String extractUsername(Claims claims) {
        return claims.get(USERNAME_CLAIM, String.class);
    }

    private SecretKey signingKey() {
        String secret = properties.getSecret();
        if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("OPS_AGENT_JWT_SECRET 必须至少包含 32 个 UTF-8 字节");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
