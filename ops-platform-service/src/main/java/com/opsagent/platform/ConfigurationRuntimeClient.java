package com.opsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.RuntimeConfigurationSnapshot;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads authenticated snapshots from allowlisted internal services; never proxies browser URLs.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class ConfigurationRuntimeClient {
    private static final Set<String> SERVICES =
            Set.of(
                    "ops-auth-service",
                    "ops-ticket-service",
                    "ops-knowledge-service",
                    "ops-rag-service",
                    "ops-platform-service",
                    "ops-agent-service",
                    "ops-gateway");
    private static final Map<String, String> EXISTING_URLS =
            Map.of(
                    "ops-auth-service",
                    "ops.agent.auth-url",
                    "ops-rag-service",
                    "ops.operations.rag-url");
    private static final Map<String, String> INTERNAL_URLS =
            Map.of(
                    "ops-auth-service",
                    "OPS_AUTH_INTERNAL_URL",
                    "ops-ticket-service",
                    "OPS_TICKET_INTERNAL_URL",
                    "ops-knowledge-service",
                    "OPS_KNOWLEDGE_INTERNAL_URL",
                    "ops-rag-service",
                    "OPS_RAG_INTERNAL_URL",
                    "ops-agent-service",
                    "OPS_AGENT_INTERNAL_URL",
                    "ops-gateway",
                    "OPS_GATEWAY_INTERNAL_URL");
    private final ObjectMapper json;
    private final Environment environment;
    private final RuntimeConfigurationSnapshot local;
    private final HttpClient http;

    @Autowired
    ConfigurationRuntimeClient(
            ObjectMapper json, Environment environment, RuntimeConfigurationSnapshot local) {
        this(
                json,
                environment,
                local,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    ConfigurationRuntimeClient(
            ObjectMapper json,
            Environment environment,
            RuntimeConfigurationSnapshot local,
            HttpClient http) {
        this.json = json;
        this.environment = environment;
        this.local = local;
        this.http = http;
    }

    ConfigCenterDtos.Overview snapshot(String serviceId) {
        var actor = SecurityUsers.current();
        if (actor.userId() <= 0
                || actor.roles().stream()
                        .noneMatch(
                                role ->
                                        Set.of("ADMIN", "ROLE_ADMIN", "OPS", "ROLE_OPS")
                                                .contains(role)))
            return unavailable(serviceId, "FORBIDDEN", "运行配置需要管理员或运维权限。");
        if (!SERVICES.contains(serviceId))
            return unavailable(serviceId, "UNSUPPORTED", "该服务尚未接入安全运行快照；仅可核对已读取的源配置。");
        try {
            if (serviceId.equals(environment.getProperty("spring.application.name"))) {
                var snapshot = local.snapshot();
                return available(
                        snapshot.serviceId(),
                        snapshot.instanceId(),
                        snapshot.observedAt(),
                        snapshot.fields());
            }
            URI base = target(serviceId);
            if (base == null)
                return unavailable(serviceId, "UNSUPPORTED", "尚未取得该服务的受信任内部地址；运行参数未核实。");
            String authorization = authorization();
            if (authorization == null)
                return unavailable(serviceId, "FORBIDDEN", "当前请求缺少可转发的登录身份。");
            var request =
                    HttpRequest.newBuilder(base.resolve("/api/runtime/configuration"))
                            .timeout(Duration.ofSeconds(3))
                            .header("Authorization", authorization)
                            .GET()
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var stream = response.body()) {
                bytes = stream.readNBytes(262145);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403)
                return unavailable(serviceId, "FORBIDDEN", "目标服务拒绝读取当前账号的运行快照。");
            if (response.statusCode() == 404)
                return unavailable(serviceId, "UNSUPPORTED", "目标服务尚未提供安全运行快照，请核对服务版本。");
            if (response.statusCode() != 200 || bytes.length > 262144)
                throw new IllegalStateException();
            var body = json.readTree(bytes);
            var data = body.path("data");
            if (body.path("code").asInt(-1) != 0
                    || !data.path("serviceId").asText().equals(serviceId)
                    || !data.path("fields").isArray()
                    || data.path("fields").size() > 100) throw new IllegalStateException();
            String instance = data.path("instanceId").asText();
            if (!instance.matches("[a-fA-F0-9-]{36}")) throw new IllegalStateException();
            Instant observed = Instant.parse(data.path("observedAt").asText());
            if (observed.isBefore(Instant.now().minusSeconds(30))
                    || observed.isAfter(Instant.now().plusSeconds(5)))
                return unavailable(serviceId, "STALE", "目标快照时间不在有效窗口内，未使用过期值。");
            List<RuntimeConfigurationSnapshot.Field> fields = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (var field : data.path("fields")) {
                var definition =
                        RuntimeConfigurationSnapshot.definitions().stream()
                                .filter(d -> d.key().equals(field.path("key").asText()))
                                .findFirst();
                if (definition.isEmpty()
                        || !field.path("value").isTextual()
                        || !seen.add(definition.get().key())) continue;
                var d = definition.get();
                String source = field.path("source").asText();
                // A target cannot inject arbitrary source names, paths or credentials into UI.
                if (!source.matches("[应用配置文件环境变量JVM系统属性启动参数Nacos源运行时未识别容器（） →、]{1,100}"))
                    source = "运行时属性源";
                fields.add(
                        new RuntimeConfigurationSnapshot.Field(
                                d.key(),
                                d.label(),
                                d.category(),
                                RuntimeConfigurationSnapshot.safeValue(
                                        field.path("value").asText()),
                                source,
                                "RUNTIME_RESOLVED"));
            }
            return available(serviceId, instance, observed, List.copyOf(fields));
        } catch (Exception ignored) {
            return unavailable(serviceId, "UNAVAILABLE", "运行快照读取失败或身份不匹配，未展示上一次值或源码默认值。");
        }
    }

    private URI target(String serviceId) {
        // Overrides are server configuration, never a value from request parameters or Nacos
        // bodies.
        String override =
                environment.getProperty("ops.configuration.runtime-targets." + serviceId, "");
        if (!override.isBlank()) return trusted(URI.create(override));
        String internalKey = INTERNAL_URLS.get(serviceId);
        String internal = internalKey == null ? "" : environment.getProperty(internalKey, "");
        if (!internal.isBlank()) return trusted(URI.create(internal));
        String key = EXISTING_URLS.get(serviceId);
        String existing = key == null ? "" : environment.getProperty(key, "");
        return existing.isBlank() ? null : trusted(URI.create(existing));
    }

    static URI trusted(URI uri) {
        if (!List.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) return null;
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean internal =
                host.equals("localhost")
                        || host.equals("[::1]")
                        || host.equals("::1")
                        || host.equals("host.docker.internal")
                        || SERVICES.contains(host)
                        || SERVICES.stream()
                                .anyMatch(
                                        service ->
                                                service.substring(4).equals(host)
                                                        || service.replace("-service", "-app")
                                                                .equals(host));
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] parts = host.split("\\.");
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            boolean valid =
                    java.util.Arrays.stream(parts).allMatch(p -> Integer.parseInt(p) <= 255);
            internal =
                    valid
                            && (a == 127
                                    || a == 10
                                    || a == 192 && b == 168
                                    || a == 172 && b >= 16 && b <= 31);
        }
        return internal ? uri : null;
    }

    private static String authorization() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            String value = attributes.getRequest().getHeader("Authorization");
            if (value != null && value.startsWith("Bearer ") && value.length() < 16384)
                return value;
        }
        return null;
    }

    private static ConfigCenterDtos.Overview available(
            String service,
            String instance,
            Instant observed,
            List<RuntimeConfigurationSnapshot.Field> fields) {
        return new ConfigCenterDtos.Overview(
                "AVAILABLE",
                service,
                instance,
                observed,
                "来自单个目标实例当前解析的配置；不代表全部实例一致，也不等同于连接正常、对象已刷新或业务验证通过。",
                fields);
    }

    static ConfigCenterDtos.Overview unavailable(String service, String status, String message) {
        return new ConfigCenterDtos.Overview(status, service, "", null, message, List.of());
    }
}
