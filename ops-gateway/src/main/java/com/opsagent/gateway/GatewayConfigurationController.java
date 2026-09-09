package com.opsagent.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.ConfigurationExecutionEvidence;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Gateway WebFlux snapshots with explicit JWT roles and independent loopback executor evidence.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class GatewayConfigurationController {
    private static final Map<String, String> FIELDS =
            Map.of(
                    "server.port", "服务监听配置端口",
                    "spring.cloud.nacos.config.enabled", "Nacos 配置开关",
                    "spring.cloud.nacos.config.server-addr", "Nacos 配置地址",
                    "spring.cloud.nacos.discovery.enabled", "Nacos 注册开关",
                    "spring.cloud.nacos.discovery.server-addr", "Nacos 注册地址",
                    "spring.cloud.sentinel.enabled", "Sentinel 开关",
                    "spring.cloud.gateway.server.webflux.httpclient.connect-timeout", "网关连接超时（毫秒）",
                    "spring.cloud.gateway.server.webflux.httpclient.response-timeout", "网关响应超时");
    private final ConfigurableEnvironment environment;
    private final GatewaySecurityProperties security;
    private final ConfigurationExecutionEvidence evidence;

    GatewayConfigurationController(
            ConfigurableEnvironment environment, GatewaySecurityProperties security) {
        this.environment = environment;
        this.security = security;
        evidence =
                new ConfigurationExecutionEvidence(
                        environment.getProperty("spring.application.name", "ops-gateway"),
                        environment.getProperty("ops.configuration.executor.secret", ""),
                        environment.getProperty("ops.configuration.managed-files", ""),
                        environment.getProperty("ops.configuration.managed-keys", ""),
                        path -> {
                            for (PropertySource<?> source : environment.getPropertySources()) {
                                if (source.getName().contains(path.getFileName().toString())
                                        && source.getName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("config")) return true;
                            }
                            return false;
                        },
                        environment.getProperty("ops.configuration.executor.trusted-host", ""));
    }

    @GetMapping("/api/runtime/configuration")
    ResponseEntity<?> snapshot(ServerWebExchange exchange) {
        if (!permitted(exchange.getRequest().getHeaders().getFirst("Authorization")))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(ApiResponse.failure(40300, "运行配置需要管理员或运维权限"));
        List<Map<String, String>> fields = new ArrayList<>();
        FIELDS.forEach(
                (key, label) -> {
                    String value = environment.getProperty(key);
                    if (value != null)
                        fields.add(
                                Map.of(
                                        "key",
                                        key,
                                        "label",
                                        label,
                                        "category",
                                        "注册与运行",
                                        "value",
                                        safe(value),
                                        "source",
                                        source(key) ? "环境变量" : "应用配置文件",
                                        "verification",
                                        "RUNTIME_RESOLVED"));
                });
        var current = evidence.snapshot(List.of());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.success(
                                Map.of(
                                        "serviceId",
                                        current.serviceId(),
                                        "instanceId",
                                        current.instanceId(),
                                        "observedAt",
                                        current.observedAt(),
                                        "fields",
                                        fields)));
    }

    @GetMapping(ConfigurationExecutionEvidence.PATH)
    ResponseEntity<?> executionEvidence(ServerWebExchange exchange) {
        var address = exchange.getRequest().getRemoteAddress();
        var headers = exchange.getRequest().getHeaders();
        if (address == null
                || !evidence.authorized(
                        address.getAddress().getHostAddress(),
                        headers.getFirst("X-Ops-Executor-Time"),
                        headers.getFirst("X-Ops-Executor-Nonce"),
                        headers.getFirst("X-Ops-Executor-Signature")))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .cacheControl(CacheControl.noStore())
                    .body(ApiResponse.failure(40300, "执行器证据读取身份不合法"));
        List<ConfigurationExecutionEvidence.Field> fields = new ArrayList<>();
        for (String key : evidence.keys()) {
            try {
                String value = value(key);
                if (value != null)
                    fields.add(
                            new ConfigurationExecutionEvidence.Field(
                                    key,
                                    ConfigurationExecutionEvidence.sensitiveKey(key)
                                            ? null
                                            : safe(value),
                                    evidence.hash(value),
                                    source(key),
                                    source(key) ? "环境变量或启动参数覆盖" : "配置文件解析值"));
            } catch (Exception ignored) {
                // Unresolved values cannot become positive adoption evidence.
            }
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(evidence.snapshot(fields)));
    }

    private boolean source(String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getName().equals("configurationProperties") || !source.containsProperty(key))
                continue;
            String name = source.getName().toLowerCase(Locale.ROOT);
            return name.contains("systemenvironment")
                    || name.contains("systemproperties")
                    || name.contains("commandline");
        }
        return false;
    }

    private String value(String key) throws Exception {
        String direct = environment.getProperty(key);
        if (direct != null) return direct;
        TreeMap<String, String> values = new TreeMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String name : enumerable.getPropertyNames()) {
                if (!name.startsWith(key + ".") && !name.startsWith(key + "[")) continue;
                String item = environment.getProperty(name);
                if (item == null) continue;
                String relative = name.substring(key.length());
                if (relative.startsWith(".")) relative = relative.substring(1);
                values.put(relative, item);
            }
        }
        return values.isEmpty() ? null : new ObjectMapper().writeValueAsString(values);
    }

    private boolean permitted(String header) {
        try {
            if (header == null
                    || !header.startsWith("Bearer ")
                    || security.getJwtSecret().length() < 32) return false;
            var claims =
                    Jwts.parser()
                            .verifyWith(
                                    Keys.hmacShaKeyFor(
                                            security.getJwtSecret()
                                                    .getBytes(StandardCharsets.UTF_8)))
                            .build()
                            .parseSignedClaims(header.substring(7))
                            .getPayload();
            List<?> roles = claims.get("roles", List.class);
            return Long.parseLong(claims.getSubject()) > 0
                    && roles != null
                    && roles.stream()
                            .anyMatch(
                                    role ->
                                            Set.of("ADMIN", "ROLE_ADMIN", "OPS", "ROLE_OPS")
                                                    .contains(role));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String safe(String value) {
        if (value.length() > 2048
                || value.contains("\n")
                || value.contains("\r")
                || value.contains("${")
                || value.contains("Bearer ")
                || value.contains("-----BEGIN")) return "******";
        String result = value.replaceAll("(://)[^/@\\s]+@", "$1******@");
        return result.contains("://") ? result.split("[?;#]", 2)[0] : result;
    }
}
