package com.opsagent.common.security;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Allowlisted, read-only projection of this process's resolved configuration, never its full env.
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class RuntimeConfigurationSnapshot {
    public record Definition(String key, String label, String category) {}

    public record Field(
            String key,
            String label,
            String category,
            String value,
            String source,
            String verification) {}

    public record Snapshot(
            String serviceId, String instanceId, Instant observedAt, List<Field> fields) {}

    private static final Pattern UNSAFE =
            Pattern.compile(
                    "(?is).*(-----BEGIN|Bearer |Basic"
                        + " |eyJ[a-zA-Z0-9_-]+\\.|(?:password|secret|token|api[_-]?key)\\s*[=:]).*");
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)(?::[^}]*)?}");
    private static final List<Definition> DEFINITIONS =
            List.of(
                    field("ops.ai.provider", "模型提供方", "模型与检索"),
                    field("ops.ai.enabled", "模型调用开关", "模型与检索"),
                    field("ops.ai.timeout-seconds", "模型请求超时（秒）", "模型与检索"),
                    field("ops.rag.top-k", "检索条数", "模型与检索"),
                    field("ops.rag.retrieval-candidates", "检索候选数", "模型与检索"),
                    field("ops.rag.rerank-enabled", "重排开关", "模型与检索"),
                    field("ops.rag.max-context-tokens", "上下文 Token 上限", "模型与检索"),
                    field("ops.ai.providers.openai.model", "OpenAI 模型", "模型与检索"),
                    field("ops.ai.providers.openai.base-url", "OpenAI 接口", "模型与检索"),
                    field("ops.ai.providers.deepseek.model", "DeepSeek 模型", "模型与检索"),
                    field("ops.ai.providers.deepseek.base-url", "DeepSeek 接口", "模型与检索"),
                    field("ops.ai.providers.kimi.model", "Kimi 模型", "模型与检索"),
                    field("ops.ai.providers.kimi.base-url", "Kimi 接口", "模型与检索"),
                    field("spring.datasource.url", "数据库连接目标", "连接配置"),
                    field("spring.datasource.hikari.maximum-pool-size", "数据库连接池上限", "连接配置"),
                    field("spring.datasource.hikari.connection-timeout", "数据库连接等待超时（毫秒）", "连接配置"),
                    field("spring.data.redis.host", "Redis 主机", "连接配置"),
                    field("spring.data.redis.port", "Redis 端口", "连接配置"),
                    field("spring.data.redis.database", "Redis 数据库编号", "连接配置"),
                    field("spring.data.redis.timeout", "Redis 读超时", "连接配置"),
                    field("spring.data.redis.connect-timeout", "Redis 连接超时", "连接配置"),
                    field("spring.rabbitmq.host", "RabbitMQ 主机", "连接配置"),
                    field("spring.rabbitmq.port", "RabbitMQ 端口", "连接配置"),
                    field("spring.rabbitmq.virtual-host", "RabbitMQ 虚拟主机", "连接配置"),
                    field("spring.rabbitmq.connection-timeout", "RabbitMQ 连接超时", "连接配置"),
                    field("spring.rabbitmq.listener.simple.retry.enabled", "消息消费重试", "连接配置"),
                    field("spring.rabbitmq.listener.simple.retry.max-attempts", "消息重试上限", "连接配置"),
                    field("spring.elasticsearch.uris", "Elasticsearch 连接目标", "连接配置"),
                    field("spring.cloud.nacos.config.enabled", "Nacos 配置开关", "注册与运行"),
                    field("spring.cloud.nacos.config.server-addr", "Nacos 配置地址", "注册与运行"),
                    field("spring.cloud.nacos.discovery.enabled", "Nacos 注册开关", "注册与运行"),
                    field("spring.cloud.nacos.discovery.server-addr", "Nacos 注册地址", "注册与运行"),
                    field("spring.cloud.sentinel.enabled", "Sentinel 开关", "注册与运行"),
                    field("server.port", "服务监听配置端口", "注册与运行"),
                    field(
                            "spring.cloud.gateway.server.webflux.httpclient.connect-timeout",
                            "网关连接超时（毫秒）",
                            "注册与运行"),
                    field(
                            "spring.cloud.gateway.server.webflux.httpclient.response-timeout",
                            "网关响应超时",
                            "注册与运行"),
                    field("ops.observability.sample-max-age-seconds", "观测样本有效期（秒）", "注册与运行"),
                    field("ops.operations.inspection-enabled", "持续巡检开关", "注册与运行"),
                    field("ops.operations.inspection-interval-ms", "巡检间隔（毫秒）", "注册与运行"));

    private final ConfigurableEnvironment environment;
    // Ephemeral process identity avoids disclosing hostnames or operating system paths.
    private final String instanceId = UUID.randomUUID().toString();

    public RuntimeConfigurationSnapshot(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public Snapshot snapshot() {
        List<Field> fields = new ArrayList<>();
        for (Definition definition : DEFINITIONS) {
            try {
                String value = environment.getProperty(definition.key());
                if (value == null || value.isBlank()) continue;
                fields.add(
                        new Field(
                                definition.key(),
                                definition.label(),
                                definition.category(),
                                safeValue(value),
                                origin(definition.key()),
                                "RUNTIME_RESOLVED"));
            } catch (RuntimeException ignored) {
                // An unresolved or ill-typed value must not fall back to a source-code default.
            }
        }
        String service = environment.getProperty("spring.application.name", "unknown");
        if (!service.matches("[a-zA-Z0-9_.-]{1,64}")) service = "unknown";
        return new Snapshot(service, instanceId, Instant.now(), List.copyOf(fields));
    }

    public static String safeValue(String raw) {
        if (raw == null || raw.length() > 2048 || raw.contains("\n") || raw.contains("\r"))
            return "******";
        String value = raw.replaceAll("(://)[^/@\\s]+@", "$1******@");
        // URL query strings and JDBC options may carry credentials; only retain the target.
        if (value.contains("://")) value = value.split("[?;#]", 2)[0];
        if (value.contains("${") || UNSAFE.matcher(value).matches() || value.length() > 240)
            return "******";
        return value;
    }

    private String origin(String key) {
        PropertySource<?> source = source(key);
        if (source == null) return "运行时配置源（来源未识别）";
        String label = sourceLabel(source.getName());
        Object raw = source.getProperty(key);
        if (raw instanceof String text) {
            var match = PLACEHOLDER.matcher(text);
            List<String> origins = new ArrayList<>();
            while (match.find()) {
                PropertySource<?> nested = source(match.group(1));
                if (nested != null) origins.add(sourceLabel(nested.getName()));
            }
            if (!origins.isEmpty())
                label += " → " + String.join("、", origins.stream().distinct().toList());
        }
        return label;
    }

    private PropertySource<?> source(String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            // This synthetic aggregate shadows the useful physical property source.
            if (source.getName().equals("configurationProperties")) continue;
            if (source.containsProperty(key)) return source;
        }
        return null;
    }

    private static String sourceLabel(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("nacos")) return "Nacos 配置源";
        if (lower.contains("systemenvironment")) return "环境变量";
        if (lower.contains("systemproperties")) return "JVM 系统属性";
        if (lower.contains("commandline")) return "启动参数";
        if (lower.contains("application") || lower.contains("config resource")) return "应用配置文件";
        if (lower.contains("server.ports")) return "运行容器";
        return "运行时属性源";
    }

    private static Definition field(String key, String label, String category) {
        return new Definition(key, label, category);
    }
}
