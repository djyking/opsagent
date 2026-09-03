package com.opsagent.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.crypto.SecretKey;

/**
 * Gateway 全局过滤器，统一生成 Trace ID 并在转发前校验 JWT 签名。
 *
 * @author heyu
 * @since 2026/9/2
 */
@Component
public class GatewaySecurityFilter implements GlobalFilter, Ordered {
    private final GatewaySecurityProperties p;

    GatewaySecurityFilter(GatewaySecurityProperties p) {
        this.p = p;
    }

    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String trace = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (trace == null || trace.isBlank()) trace = UUID.randomUUID().toString().replace("-", "");
        var request = exchange.getRequest().mutate().header("X-Trace-Id", trace).build();
        exchange.getResponse().getHeaders().set("X-Trace-Id", trace);
        ServerWebExchange next = exchange.mutate().request(request).build();
        if (p.getPublicPaths().stream().anyMatch(path::equals) || path.startsWith("/actuator/"))
            return chain.filter(next);
        String h = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (h == null || !h.startsWith("Bearer ") || !valid(h.substring(7)))
            return unauthorized(exchange);
        return chain.filter(next);
    }

    private boolean valid(String token) {
        try {
            byte[] bytes = p.getJwtSecret().getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) return false;
            SecretKey key = Keys.hmacShaKeyFor(bytes);
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange e) {
        e.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        e.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body =
                "{\"code\":40100,\"message\":\"未登录或登录已过期\",\"data\":null}"
                        .getBytes(StandardCharsets.UTF_8);
        return e.getResponse().writeWith(Mono.just(e.getResponse().bufferFactory().wrap(body)));
    }

    public int getOrder() {
        return -100;
    }
}
