package com.opsagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * WebFlux configuration entry rejects ordinary users and preserves service-specific identity.
 *
 * @author heyu
 * @since 2026/9/3
 */
class GatewayConfigurationControllerTest {
    @Test
    void publicSnapshotRequiresValidRoleAndReturnsGatewayIdentity() {
        var security = new GatewaySecurityProperties();
        security.setJwtSecret("gateway-snapshot-test-secret-0123456789");
        var controller =
                new GatewayConfigurationController(
                        new MockEnvironment()
                                .withProperty("spring.application.name", "ops-gateway")
                                .withProperty("server.port", "18080"),
                        security);
        for (String role : List.of("USER", "ADMIN")) {
            String token =
                    Jwts.builder()
                            .subject("1")
                            .claim("roles", List.of(role))
                            .expiration(new Date(System.currentTimeMillis() + 60000))
                            .signWith(
                                    Keys.hmacShaKeyFor(
                                            security.getJwtSecret()
                                                    .getBytes(StandardCharsets.UTF_8)))
                            .compact();
            var exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.get("/api/runtime/configuration")
                                    .header("Authorization", "Bearer " + token));
            var result = controller.snapshot(exchange);
            assertThat(result.getStatusCode())
                    .isEqualTo(role.equals("ADMIN") ? HttpStatus.OK : HttpStatus.FORBIDDEN);
            if (role.equals("ADMIN"))
                assertThat(result.getBody().toString()).contains("ops-gateway", "18080");
        }
        assertThat(
                        controller
                                .executionEvidence(
                                        MockServerWebExchange.from(
                                                MockServerHttpRequest.get(
                                                        "/internal/runtime/configuration-evidence")))
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
