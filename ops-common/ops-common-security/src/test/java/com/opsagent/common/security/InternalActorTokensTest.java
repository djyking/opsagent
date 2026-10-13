package com.opsagent.common.security;

import static org.assertj.core.api.Assertions.*;

import com.opsagent.common.core.BusinessException;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 验证内部身份与登录令牌隔离、接收服务限制、签名及租约/短期有效期。
 *
 * @author heyu
 * @since 2026/9/3
 */
class InternalActorTokensTest {
    private static final String SECRET = "agent-only-unit-test-secret-over-32-bytes";
    private final InternalActorTokens tokens = new InternalActorTokens(SECRET);

    @Test
    void shouldPreserveActorAndLimitTokenToAudienceAndLease() {
        var ctx = context(Instant.now().plusSeconds(30));
        String token = tokens.issue("rag", ctx);
        var restored = tokens.verify("Bearer " + token, "rag");
        assertThat(restored.userId()).isEqualTo(-55);
        assertThat(restored.roles()).containsExactly("DEMO");
        assertThat(restored.runId()).isEqualTo("run-1");
        assertThat(restored.validUntil().getEpochSecond())
                .isEqualTo(ctx.validUntil().getEpochSecond());
        assertThatThrownBy(() -> tokens.verify("Bearer " + token, "ticket"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                new InternalActorTokens(SECRET + "other")
                                        .verify("Bearer " + token, "rag"))
                .isInstanceOf(BusinessException.class);
        var claims =
                Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
        assertThat(claims.getExpiration().toInstant()).isBeforeOrEqualTo(ctx.validUntil());
    }

    @Test
    void shouldRejectLongLivedInternalTokenAndOrdinaryLoginToken() {
        Instant now = Instant.now();
        String longLived =
                Jwts.builder()
                        .issuer("ops-agent-internal")
                        .audience()
                        .add("rag")
                        .and()
                        .subject("1")
                        .claim("username", "test")
                        .claim("roles", List.of("ADMIN"))
                        .claim("runId", "run-1")
                        .claim("leaseUntil", now.plusSeconds(3600).getEpochSecond())
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(now.plusSeconds(300)))
                        .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                        .compact();
        assertThatThrownBy(() -> tokens.verify("Bearer " + longLived, "rag"))
                .isInstanceOf(BusinessException.class);
        var properties = new JwtProperties();
        properties.setSecret(SECRET);
        String login = new JwtService(properties).issue(1, "test", List.of("ADMIN")).token();
        assertThatThrownBy(() -> tokens.verify("Bearer " + login, "rag"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tokens.issue("rag", context(now.minusSeconds(1))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldStartWithoutSecretButRejectEveryCall() {
        var disabled = new InternalActorTokens("");
        assertThat(disabled.configured()).isFalse();
        assertThatThrownBy(() -> disabled.issue("rag", context(Instant.now().plusSeconds(60))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("INTERNAL_AUTH_NOT_CONFIGURED");
        assertThatThrownBy(() -> disabled.verify("Bearer any", "rag"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("INTERNAL_AUTH_NOT_CONFIGURED");
    }

    private InternalActorTokens.Context context(Instant until) {
        return new InternalActorTokens.Context(
                -55, "visitor", List.of("DEMO"), "run-1", "demo-order", until);
    }
}
