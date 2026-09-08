package com.opsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.RuntimeConfigurationSnapshot;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Nacos catalog with explicitly read-only deployment provenance.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class ConfigCenterService {
    private final NacosConfigurationClient nacos;
    private final ConfigurationMasker masker;
    private final ConfigurationRuntimeClient runtime;

    @Value("${ops.configuration.bindings-json:{}}")
    private String bindingsJson = "{}";

    ConfigCenterService(NacosConfigurationClient nacos, ConfigurationMasker masker) {
        this(nacos, masker, null);
    }

    @Autowired
    ConfigCenterService(
            NacosConfigurationClient nacos,
            ConfigurationMasker masker,
            ConfigurationRuntimeClient runtime) {
        this.nacos = nacos;
        this.masker = masker;
        this.runtime = runtime;
    }

    ConfigCenterDtos.Catalog catalog(String ciCode) {
        SecurityUsers.current();
        var all = catalogSnapshot();
        var items =
                all.items().stream()
                        .filter(
                                item ->
                                        ciCode == null
                                                || ciCode.isBlank()
                                                || item.identity().targetScope().contains(ciCode))
                        .toList();
        return new ConfigCenterDtos.Catalog(all.status(), items, all.message(), all.observedAt());
    }

    ConfigCenterDtos.Summary summary(String ciCode) {
        var result = catalog(ciCode);
        var sources =
                result.items().stream()
                        .map(ConfigCenterDtos.Item::source)
                        .distinct()
                        .map(
                                source ->
                                        new ConfigCenterDtos.Source(
                                                source,
                                                (int)
                                                        result.items().stream()
                                                                .filter(
                                                                        i ->
                                                                                i.source()
                                                                                        .equals(
                                                                                                source))
                                                                .count(),
                                                source.equals("NACOS")
                                                        ? result.status()
                                                        : "UNSUPPORTED"))
                        .toList();
        return new ConfigCenterDtos.Summary(
                result.status(),
                result.status().equals("AVAILABLE")
                        ? (int)
                                result.items().stream()
                                        .filter(i -> i.capabilities().canRead())
                                        .count()
                        : null,
                (int) result.items().stream().filter(ConfigCenterDtos.Item::editable).count(),
                sources,
                result.message(),
                result.observedAt());
    }

    private synchronized ConfigCenterDtos.Catalog catalogSnapshot() {
        // No cross-account configuration cache; source identity and current permissions are
        // rebuilt.
        List<ConfigCenterDtos.Item> items = new ArrayList<>();
        String status = "AVAILABLE";
        String message = "实际读取 Nacos 配置目录，最多 500 项。正文及历史统一脱敏；部署配置由部署系统管理。";
        try {
            for (var item : nacos.catalog()) {
                String path = managedPath(item.group(), item.dataId());
                var identity =
                        identity("NACOS", item.group(), item.dataId(), service(item.dataId()));
                boolean managed =
                        item.group().equals("OPSAGENT_DEMO")
                                && item.dataId().equals("ops-demo-order-business.json")
                                && identity.targetScope().equals(List.of(DemoTargetDtos.TARGET));
                boolean editable = managed && administrator();
                items.add(
                        new ConfigCenterDtos.Item(
                                key(identity),
                                item.dataId(),
                                service(item.dataId()),
                                "NACOS",
                                nacos.namespace(),
                                item.group(),
                                item.dataId(),
                                item.type(),
                                editable,
                                path,
                                "AVAILABLE",
                                managed
                                        ? "仅三个订单业务字段支持经审批的结构化变更；原始源中其余字段保留。"
                                        : "实际 Nacos 源配置片段；不代表应用全部有效配置。相同内容可能由部署分别写入不同来源。",
                                item.modifiedAt(),
                                identity,
                                capabilities(true, editable, managed),
                                identity.targetScope().size() > 1));
            }
        } catch (Exception ignored) {
            status = failure(ignored);
            message = "Nacos 配置源暂不可读，数量未知。部署来源清单仍可浏览；不会把读取失败显示成空配置。";
        }
        for (String name :
                List.of(
                        "ops-auth-service",
                        "ops-ticket-service",
                        "ops-knowledge-service",
                        "ops-rag-service",
                        "ops-platform-service",
                        "ops-agent-service",
                        "ops-gateway")) {
            items.add(
                    deployment(
                            name + "-env",
                            name,
                            "ENV",
                            "应用运行参数",
                            "通过环境变量注入数据库、缓存、模型与内部地址；密钥由部署系统注入，此处不读取宿主环境。"));
        }
        items.add(
                deployment(
                        "deployment-compose",
                        "deployment",
                        "COMPOSE",
                        "Docker Compose 部署",
                        "deploy/public/compose.yaml；容器网络、资源限制与挂载由部署系统管理。"));
        for (String name : List.of("prometheus", "rabbitmq", "redis"))
            items.add(
                    deployment(
                            name + "-local",
                            name,
                            "LOCAL_FILE",
                            name + " 运行配置",
                            "配置由部署系统管理，不提供任意宿主机文件读取或修改。"));
        items.add(
                deployment(
                        "platform-db",
                        "ops-platform-service",
                        "DATABASE",
                        "平台业务配置",
                        "服务目录、自动化策略与审批状态由各业务 API 维护。"));
        items.add(
                deployment(
                        "rag-model",
                        "ops-rag-service",
                        "EXTERNAL",
                        "模型服务配置",
                        "外部模型接口由应用配置选择，运行状态在 AI 问答中核对。"));
        items.add(
                deployment(
                        "rag-secrets",
                        "ops-rag-service",
                        "SECRET",
                        "模型与应用凭据",
                        "******；凭据由部署系统管理，不向前端读取原值。"));
        return new ConfigCenterDtos.Catalog(status, List.copyOf(items), message, Instant.now());
    }

    ConfigCenterDtos.Detail detail(String id) {
        SecurityUsers.current();
        var item = resolve(id);
        if (!item.source().equals("NACOS"))
            return new ConfigCenterDtos.Detail(
                    item,
                    "UNSUPPORTED",
                    "",
                    "",
                    "该来源尚未接入真实快照，仅登记管理边界。" + item.description(),
                    Instant.now(),
                    overview(item, ""));
        try {
            var raw = nacos.content(item.dataId(), item.group());
            String masked = masker.mask(raw.value(), raw.type());
            if (masked.isBlank() || masked.startsWith(ConfigurationMasker.MASK))
                return new ConfigCenterDtos.Detail(
                        item,
                        "INVALID_CONTENT",
                        "",
                        raw.revision(),
                        "实际源内容无法安全解析，已隐藏；未返回模板。",
                        Instant.now(),
                        overview(item, ""));
            return new ConfigCenterDtos.Detail(
                    item,
                    "AVAILABLE",
                    masked,
                    raw.revision(),
                    "这是实际源配置片段，安全预览规范化为 JSON；不是应用完整有效配置。敏感值已隐藏，预览不允许直接写回。",
                    Instant.now(),
                    overview(item, masked));
        } catch (Exception ignored) {
            return new ConfigCenterDtos.Detail(
                    item,
                    failure(ignored),
                    "",
                    "",
                    failureMessage(failure(ignored)),
                    Instant.now(),
                    overview(item, ""));
        }
    }

    private ConfigCenterDtos.Overview overview(ConfigCenterDtos.Item item, String masked) {
        // Shared sources cannot be attributed to just the service inferred from their filename.
        if (item.shared())
            return ConfigurationRuntimeClient.unavailable(
                    item.serviceId(), "SHARED_SOURCE", "此配置关联多个服务，请从具体服务入口核对运行值；源配置不代表每个目标都已应用。");
        String serviceId =
                item.identity().targetScope().size() == 1
                        ? item.identity().targetScope().get(0)
                        : item.serviceId();
        var snapshot =
                runtime == null
                        ? ConfigurationRuntimeClient.unavailable(
                                serviceId, "UNSUPPORTED", "安全运行快照尚未接入。")
                        : runtime.snapshot(serviceId);
        if (snapshot.status().equals("AVAILABLE")) return snapshot;
        List<RuntimeConfigurationSnapshot.Field> fields = new ArrayList<>();
        boolean markerOnly = false;
        if (!masked.isBlank()) {
            try {
                var root = new ObjectMapper().readTree(masked);
                for (var definition : RuntimeConfigurationSnapshot.definitions()) {
                    var value = root.path(definition.key());
                    if (value.isMissingNode()) {
                        value = root;
                        for (String part : definition.key().split("\\.")) value = value.path(part);
                    }
                    if (!value.isValueNode() || value.isNull()) continue;
                    fields.add(
                            new RuntimeConfigurationSnapshot.Field(
                                    definition.key(),
                                    definition.label(),
                                    definition.category(),
                                    RuntimeConfigurationSnapshot.safeValue(value.asText()),
                                    "Nacos 源配置",
                                    "SOURCE_ONLY"));
                }
                markerOnly =
                        fields.isEmpty()
                                && (root.path("info").path("middleware").has("nacos-config")
                                        || root.has("info.middleware.nacos-config"));
            } catch (Exception ignored) {
                // Already masked source is optional evidence; never substitute a generated
                // template.
            }
        }
        String message = snapshot.message();
        if (markerOnly) message += " 当前 Nacos 文件仅含接入标记，connected 是静态配置文字，不能证明连接正常。";
        if (!fields.isEmpty()) message += " 下列值仅来自当前 Nacos 源，目标是否采用尚未核实。";
        return new ConfigCenterDtos.Overview(
                snapshot.status(), serviceId, "", null, message, List.copyOf(fields));
    }

    ConfigCenterDtos.History history(String id) {
        SecurityUsers.current();
        var item = resolve(id);
        if (!item.source().equals("NACOS"))
            return new ConfigCenterDtos.History("UNSUPPORTED", List.of(), "此来源尚未接入版本快照。");
        try {
            return new ConfigCenterDtos.History(
                    "AVAILABLE",
                    nacos.history(item.dataId(), item.group()).stream()
                            .map(
                                    v ->
                                            new ConfigCenterDtos.Version(
                                                    v.id(),
                                                    v.actor(),
                                                    v.modifiedAt(),
                                                    v.operation()))
                            .toList(),
                    "来自 Nacos 的最近 20 次版本记录。");
        } catch (Exception ignored) {
            return new ConfigCenterDtos.History(
                    failure(ignored), List.of(), failureMessage(failure(ignored)));
        }
    }

    ConfigCenterDtos.Diff diff(String id, long versionId) {
        SecurityUsers.current();
        var item = resolve(id);
        if (!item.source().equals("NACOS"))
            throw new BusinessException(ErrorCode.VALIDATION, "部署配置版本由部署系统管理");
        try {
            var current = nacos.content(item.dataId(), item.group());
            var previous = nacos.version(item.dataId(), item.group(), versionId);
            return new ConfigCenterDtos.Diff(
                    "AVAILABLE",
                    versionId,
                    masker.mask(current.value(), current.type()),
                    masker.mask(previous.value(), previous.type()),
                    "比较历史版本与当前版本；敏感值不会出现在差异中。");
        } catch (Exception ignored) {
            return new ConfigCenterDtos.Diff(
                    failure(ignored), versionId, "", "", failureMessage(failure(ignored)));
        }
    }

    ConfigCenterDtos.Item resolve(String id) {
        var catalog = catalogSnapshot();
        return catalog.items().stream()
                .filter(i -> i.id().equals(id))
                .findFirst()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        catalog.status().equals("AVAILABLE")
                                                ? ErrorCode.NOT_FOUND
                                                : catalog.status().equals("FORBIDDEN")
                                                        ? ErrorCode.FORBIDDEN
                                                        : ErrorCode.MIDDLEWARE_UNAVAILABLE,
                                        catalog.status().equals("AVAILABLE")
                                                ? "配置不在当前完整身份的纳管目录内。"
                                                : failureMessage(catalog.status())));
    }

    private ConfigCenterDtos.Item deployment(
            String id, String service, String source, String name, String description) {
        var identity = identity(source, "", id, service);
        return new ConfigCenterDtos.Item(
                key(identity),
                name,
                service,
                source,
                "",
                "",
                id,
                "",
                false,
                "",
                "UNSUPPORTED",
                description,
                "",
                identity,
                capabilities(false, false, false),
                false);
    }

    static String key(ConfigCenterDtos.Identity identity) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        String.join(
                                        "\n",
                                        identity.sourceType(),
                                        identity.sourceInstanceId(),
                                        identity.environment(),
                                        identity.namespaceId(),
                                        identity.group(),
                                        identity.dataId(),
                                        String.join(",", identity.targetScope()))
                                .getBytes(StandardCharsets.UTF_8));
    }

    private static String managedPath(String group, String id) {
        if (group.equals("OPSAGENT_DEMO")
                && (id.equals("ops-demo-order-business.json")
                        || id.equals("ops-demo-order-runtime.json")))
            return "/observability/config/managed?ciCode=ops-demo-order-service";
        if (group.equals("DEFAULT_GROUP") && id.startsWith("ops-rag-sentinel-"))
            return "/observability/traffic?ciCode=ops-rag-service";
        return "";
    }

    private static String service(String id) {
        if (id.startsWith("ops-demo-notification")) return "ops-demo-notification-service";
        if (id.startsWith("ops-rag-")) return "ops-rag-service";
        if (id.startsWith("ops-demo-order")) return "ops-demo-order-service";
        for (String value :
                List.of(
                        "ops-auth-service",
                        "ops-ticket-service",
                        "ops-knowledge-service",
                        "ops-platform-service",
                        "ops-agent-service",
                        "ops-gateway")) if (id.startsWith(value)) return value;
        return "deployment";
    }

    private ConfigCenterDtos.Identity identity(
            String source, String group, String id, String service) {
        return new ConfigCenterDtos.Identity(
                source,
                source.equals("NACOS") ? nacos.sourceInstanceId() : "unintegrated-deployment",
                nacos.environment(),
                source.equals("NACOS") ? nacos.namespace() : "",
                group,
                id,
                targets(source, group, id, service));
    }

    private List<String> targets(String source, String group, String id, String fallback) {
        if (!source.equals("NACOS")) return List.of(fallback);
        try {
            if (bindingsJson.length() > 32768) throw new IllegalArgumentException();
            var binding =
                    new ObjectMapper()
                            .readTree(bindingsJson)
                            .path(nacos.namespace() + "|" + group + "|" + id);
            if (binding.isMissingNode()) return List.of(fallback);
            if (!binding.isArray() || binding.isEmpty() || binding.size() > 50)
                throw new IllegalArgumentException();
            var targets = new ArrayList<String>();
            for (var target : binding) {
                if (!target.isTextual() || !target.asText().matches("[a-zA-Z0-9_.-]{1,64}"))
                    throw new IllegalArgumentException();
                targets.add(target.asText());
            }
            return targets.stream().distinct().sorted().toList();
        } catch (Exception invalid) {
            throw new NacosConfigurationClient.SourceFailure("INVALID_CONTENT");
        }
    }

    private ConfigCenterDtos.Capabilities capabilities(
            boolean readable, boolean editable, boolean verifiable) {
        String reason = !readable ? "尚未接入真实源快照" : "仅纳管订单业务白名单字段，其他来源只读";
        if (readable && verifiable && !editable) reason = "变更需要管理员权限并通过现有审批";
        return new ConfigCenterDtos.Capabilities(
                readable,
                readable,
                editable,
                editable,
                editable,
                readable && verifiable,
                Map.of(
                        "read",
                        readable ? "实际源读取" : reason,
                        "diff",
                        readable ? "真实Nacos历史" : reason,
                        "edit",
                        editable ? "白名单结构化patch" : reason,
                        "publish",
                        editable ? "须通过现有Agent审批" : reason,
                        "rollback",
                        editable ? "新建回退提案并校验当前版本" : reason,
                        "verifyApplied",
                        verifiable ? "隔离订单真实目标反馈与业务核对" : "未接入目标实例应用反馈"));
    }

    private static boolean administrator() {
        var actor = SecurityUsers.current();
        return actor.userId() > 0
                && actor.roles().stream()
                        .anyMatch(role -> role.equals("ADMIN") || role.equals("ROLE_ADMIN"));
    }

    private String failure(Exception cause) {
        return cause instanceof NacosConfigurationClient.SourceFailure source
                ? source.code()
                : !nacos.enabled() ? "UNSUPPORTED" : "UPSTREAM_UNAVAILABLE";
    }

    private static String failureMessage(String status) {
        return switch (status) {
            case "NOT_FOUND" -> "指定完整身份的源配置不存在。";
            case "FORBIDDEN" -> "配置源拒绝访问，请核对上游读取权限。";
            case "UNSUPPORTED" -> "此来源未接入或未启用实际配置读取。";
            case "INVALID_CONTENT" -> "来源身份或正文结构不匹配，已拒绝显示。";
            default -> "配置源暂时不可用或读取超时，未显示默认内容。";
        };
    }
}
