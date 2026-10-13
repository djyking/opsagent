package com.opsagent.common.security;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

/**
 * 使用独立密钥签发短期、指定接收服务的运行身份，不保存或复用用户登录 JWT。
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class InternalActorTokens {
    private static final String ISSUER = "ops-agent-internal";
    private final SecretKey key;

    public InternalActorTokens(String secret) {
        if (secret == null || secret.isBlank()) {
            key = null;
        } else {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalArgumentException(
                        "OPS_AGENT_INTERNAL_SECRET 必须至少包含 32 个 UTF-8 字节");
            }
            key = Keys.hmacShaKeyFor(bytes);
        }
    }

    public boolean configured() {
        return key != null;
    }

    public String issue(String audience, Context ctx) {
        requireConfigured();
        validateAudience(audience);
        validateContext(ctx);
        Instant now = Instant.now();
        Instant expiry =
                ctx.validUntil().isBefore(now.plusSeconds(120))
                        ? ctx.validUntil()
                        : now.plusSeconds(120);
        if (expiry.getEpochSecond() <= now.getEpochSecond()) throw invalid();
        return Jwts.builder()
                .issuer(ISSUER)
                .audience()
                .add(audience)
                .and()
                .id(UUID.randomUUID().toString())
                .subject(Long.toString(ctx.userId()))
                .claim("username", ctx.username())
                .claim("roles", ctx.roles())
                .claim("runId", ctx.runId())
                .claim("targetCode", ctx.targetCode())
                .claim("leaseUntil", ctx.validUntil().getEpochSecond())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    public Context verify(String authorizationHeader, String audience) {
        requireConfigured();
        validateAudience(audience);
        if (authorizationHeader == null
                || !authorizationHeader.startsWith("Bearer ")
                || authorizationHeader.length() > 10000) throw invalid();
        try {
            Claims claims =
                    Jwts.parser()
                            .verifyWith(key)
                            .requireIssuer(ISSUER)
                            .build()
                            .parseSignedClaims(authorizationHeader.substring(7))
                            .getPayload();
            Instant now = Instant.now();
            if (claims.getAudience() == null
                    || claims.getAudience().size() != 1
                    || !claims.getAudience().contains(audience)
                    || claims.getIssuedAt() == null
                    || claims.getExpiration() == null) throw invalid();
            Instant issued = claims.getIssuedAt().toInstant();
            Instant expiry = claims.getExpiration().toInstant();
            Number lease = claims.get("leaseUntil", Number.class);
            if (lease == null
                    || !expiry.isAfter(now)
                    || issued.isAfter(now.plusSeconds(5))
                    || expiry.isAfter(issued.plusSeconds(120))
                    || !expiry.isAfter(issued)
                    || expiry.getEpochSecond() > lease.longValue()) throw invalid();
            List<?> rawRoles = claims.get("roles", List.class);
            if (rawRoles == null || rawRoles.stream().anyMatch(role -> !(role instanceof String)))
                throw invalid();
            Context ctx =
                    new Context(
                            Long.parseLong(claims.getSubject()),
                            claims.get("username", String.class),
                            rawRoles.stream().map(String.class::cast).toList(),
                            claims.get("runId", String.class),
                            claims.get("targetCode", String.class),
                            Instant.ofEpochSecond(lease.longValue()));
            validateContext(ctx);
            return ctx;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "INTERNAL_AUTH_NOT_CONFIGURED");
        }
    }

    private void validateAudience(String audience) {
        if (audience == null || !audience.matches("[a-z][a-z0-9-]{0,63}")) throw invalid();
    }

    private void validateContext(Context ctx) {
        if (ctx == null
                || ctx.userId() == 0
                || ctx.username() == null
                || ctx.username().isBlank()
                || ctx.username().length() > 128
                || ctx.runId() == null
                || ctx.runId().isBlank()
                || ctx.runId().length() > 128
                || ctx.targetCode() == null
                || ctx.targetCode().length() > 128
                || ctx.validUntil() == null
                || !ctx.validUntil().isAfter(Instant.now())
                || ctx.roles() == null
                || ctx.roles().isEmpty()
                || ctx.roles().size() > 16
                || ctx.roles().stream()
                        .anyMatch(role -> role == null || !role.matches("[A-Z_]{1,64}"))) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.UNAUTHENTICATED, "INTERNAL_AUTH_INVALID");
    }

    /**
     * 运行发起者与租约边界；roles 是权限上限，目标服务仍需重新核验当前权限。
     *
     * @author heyu
     * @since 2026/9/3
     */
    public record Context(
            long userId,
            String username,
            List<String> roles,
            String runId,
            String targetCode,
            Instant validUntil) {
        public Context {
            roles = roles == null ? List.of() : List.copyOf(roles);
            targetCode = targetCode == null ? "" : targetCode;
        }
    }
}
