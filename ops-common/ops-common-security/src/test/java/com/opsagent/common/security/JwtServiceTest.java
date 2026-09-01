package com.opsagent.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
    @Test
    void issuesAndParsesAllRequiredClaims() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("unit-test-secret-that-is-at-least-32-bytes");
        properties.setAccessTokenTtl(Duration.ofMinutes(5));
        JwtService service = new JwtService(properties);

        IssuedToken issued = service.issue(42L, "ops", List.of("OPS", "USER"));
        OpsPrincipal principal = service.parse(issued.token());

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.username()).isEqualTo("ops");
        assertThat(principal.tokenId()).isEqualTo(issued.tokenId());
        assertThat(principal.roles()).containsExactly("OPS", "USER");
        assertThat(issued.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void rejectsShortSecrets() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("too-short");
        assertThatThrownBy(() -> new JwtService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
