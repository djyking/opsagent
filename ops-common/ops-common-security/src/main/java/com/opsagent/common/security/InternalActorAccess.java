package com.opsagent.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 每次内部调用重新核验 Auth 身份和租约，并将有效权限限制为当前权限与运行快照交集。
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class InternalActorAccess {
    private final InternalActorTokens tokens;
    private final String authUrl;
    private final RestClient http;

    public InternalActorAccess(InternalActorTokens tokens, String authUrl) {
        this.tokens = tokens;
        this.authUrl = authUrl == null ? "" : authUrl.replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        http = RestClient.builder().requestFactory(factory).build();
    }

    public InternalActorTokens.Context verify(String authorizationHeader, String audience) {
        var claimed = tokens.verify(authorizationHeader, audience);
        try {
            URI uri = URI.create(authUrl);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                throw new IllegalArgumentException("Invalid internal Auth URL");
            }
            JsonNode envelope =
                    http.get()
                            .uri(authUrl + "/internal/agent/actors/" + claimed.userId())
                            .headers(
                                    headers -> headers.setBearerAuth(tokens.issue("auth", claimed)))
                            .retrieve()
                            .body(JsonNode.class);
            if (envelope == null || !envelope.has("code") || envelope.path("code").asInt() != 0) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "ACTOR_REVALIDATION_DENIED");
            }
            JsonNode actor = envelope.path("data");
            if (!actor.path("active").asBoolean(false)
                    || !actor.path("userId").canConvertToLong()
                    || actor.path("userId").asLong() != claimed.userId()
                    || !actor.path("roles").isArray()
                    || !actor.path("username").isTextual()
                    || actor.path("username").asText().isBlank()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "ACTOR_INACTIVE");
            }
            Instant until = claimed.validUntil();
            if (actor.hasNonNull("expiresAt")) {
                Instant actual = Instant.parse(actor.path("expiresAt").asText());
                if (actual.isBefore(until)) until = actual;
            } else if (claimed.userId() < 0) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "ACTOR_LEASE_REQUIRED");
            }
            List<String> currentRoles = new ArrayList<>();
            actor.path("roles")
                    .forEach(
                            role -> {
                                if (role.isTextual())
                                    currentRoles.add(normalizeRole(role.asText()));
                            });
            List<String> intersection =
                    claimed.roles().stream()
                            .map(InternalActorAccess::normalizeRole)
                            .filter(currentRoles::contains)
                            .distinct()
                            .toList();
            if (intersection.isEmpty() || !until.isAfter(Instant.now())) {
                throw new BusinessException(
                        ErrorCode.FORBIDDEN, "ACTOR_PERMISSION_REVOKED_OR_EXPIRED");
            }
            return new InternalActorTokens.Context(
                    claimed.userId(),
                    actor.path("username").asText(),
                    intersection,
                    claimed.runId(),
                    claimed.targetCode(),
                    until);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "ACTOR_REVALIDATION_UNAVAILABLE");
        }
    }

    public static Scope open(InternalActorTokens.Context context) {
        return new Scope(context);
    }

    private static String normalizeRole(String role) {
        return role.startsWith("ROLE_") ? role.substring(5) : role;
    }

    /**
     * 仅在当前线程的调用作用域恢复主体；关闭时还原此前上下文，避免线程池身份串用。
     *
     * @author heyu
     * @since 2026/9/3
     */
    public static final class Scope implements AutoCloseable {
        private final SecurityContext previous;
        private boolean closed;

        private Scope(InternalActorTokens.Context context) {
            previous = SecurityContextHolder.getContext();
            var next = SecurityContextHolder.createEmptyContext();
            var principal =
                    new OpsPrincipal(
                            context.userId(),
                            context.username(),
                            "internal:" + context.runId(),
                            context.roles());
            var authorities =
                    context.roles().stream()
                            .map(InternalActorAccess::normalizeRole)
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .toList();
            next.setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, authorities));
            SecurityContextHolder.setContext(next);
        }

        @Override
        public void close() {
            if (!closed) {
                SecurityContextHolder.setContext(previous);
                closed = true;
            }
        }
    }
}
