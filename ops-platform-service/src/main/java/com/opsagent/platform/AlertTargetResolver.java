package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolve alert identity from explicit canonical labels and the current CMDB, without guessing
 * jobs.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class AlertTargetResolver {
    private static final Map<String, String> ALIASES =
            Map.of(
                    "opsagent-gateway", "ops-gateway",
                    "opsagent-auth", "ops-auth-service",
                    "opsagent-ticket", "ops-ticket-service",
                    "opsagent-knowledge", "ops-knowledge-service",
                    "opsagent-rag", "ops-rag-service",
                    "opsagent-platform", "ops-platform-service",
                    "opsagent-agent", "ops-agent-service");
    private static final List<String> FIELDS =
            List.of("service_ci_code", "ci_code", "service", "job", "environment");
    private final ItsmPlatformRepository cmdb;

    AlertTargetResolver(ItsmPlatformRepository cmdb) {
        this.cmdb = cmdb;
    }

    Resolution resolve(JsonNode labels) {
        if (labels == null || !labels.isObject())
            return unresolved("LABEL_INVALID", "告警标签格式无效，等待核对原始告警记录");
        for (String field : FIELDS) {
            if (labels.has(field)
                    && (!labels.path(field).isTextual()
                            || labels.path(field).asText().length() > 128))
                return unresolved("LABEL_INVALID", "告警服务或环境标签无效，等待核对原始告警记录");
        }
        String target;
        String source;
        if (labels.has("service_ci_code") || labels.has("ci_code")) {
            if (labels.has("service_ci_code")
                    && labels.has("ci_code")
                    && !labels.path("service_ci_code")
                            .asText()
                            .equals(labels.path("ci_code").asText()))
                return unresolved(
                        "CANONICAL_CONFLICT", "告警的 service_ci_code 与 ci_code 不一致，需核对规范 CI 标签");
            source = labels.has("service_ci_code") ? "service_ci_code" : "ci_code";
            target = labels.path(source).asText();
        } else {
            String service = labels.path("service").asText();
            String job = labels.path("job").asText();
            String jobTarget = ALIASES.get(job);
            if (!service.isBlank()) {
                target = ALIASES.getOrDefault(service, service);
                source = ALIASES.containsKey(service) ? "service_alias" : "service";
                if (jobTarget != null && !jobTarget.equals(target))
                    return unresolved("ALIAS_CONFLICT", "告警 service 与已登记 job 别名指向不同服务，等待人工核对");
            } else if (jobTarget != null) {
                target = jobTarget;
                source = "job_alias";
            } else {
                return unresolved("ALIAS_MISSING", "告警缺少规范 CI 标签，采集 job 未登记服务映射，等待补充实际受影响服务");
            }
        }
        if (!target.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
            return unresolved("TARGET_INVALID", "告警的服务标识为空或格式无效，等待核对规范 CI 标签");
        Map<String, Object> ci = cmdb.ci(target);
        if (ci == null || !"ACTIVE".equals(ci.get("status")))
            return unresolved("TARGET_NOT_ACTIVE", "告警服务标识未命中启用的 CMDB 服务，等待核对规范 CI 标签");
        String environment = environment(labels.path("environment").asText());
        if (environment.isBlank())
            return unresolved("ENVIRONMENT_MISSING", "告警缺少明确环境标签，不能自动绑定恢复现场");
        if (!environment.matches("[A-Z0-9_-]{1,32}") || !environment.equals(ci.get("environment")))
            return unresolved("ENVIRONMENT_MISMATCH", "告警环境与 CMDB 服务环境不一致，不能自动绑定恢复现场");
        return new Resolution("MATCHED", target, environment, "已核对规范 CI 与 CMDB 环境", source);
    }

    private static String environment(String raw) {
        return switch (raw) {
            case "CORE" -> "PROD";
            case "ISOLATED", "ISOLATED_DEMO" -> "DEMO";
            default -> raw;
        };
    }

    private static Resolution unresolved(String code, String message) {
        return new Resolution(code, "", "", message, "UNRESOLVED");
    }

    record Resolution(
            String status, String targetCode, String environment, String message, String source) {
        boolean matched() {
            return "MATCHED".equals(status);
        }
    }
}
