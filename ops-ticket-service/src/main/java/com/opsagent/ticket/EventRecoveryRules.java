package com.opsagent.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configured technical recovery baseline; business confirmation remains a separate actor record.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class EventRecoveryRules {
    private static final Set<String> JAVA_TARGETS =
            Set.of(
                    "ops-gateway",
                    "ops-auth-service",
                    "ops-ticket-service",
                    "ops-knowledge-service",
                    "ops-rag-service",
                    "ops-platform-service",
                    "ops-agent-service");
    private static final Map<String, List<String>> NATIVE_TARGETS =
            Map.ofEntries(
                    Map.entry(
                            "mysql",
                            List.of(
                                    "infrastructureReadSuccess",
                                    "infrastructureAuthSuccess",
                                    "mysqlQuerySuccess",
                                    "mysqlConnections",
                                    "mysqlMaxConnections")),
                    Map.entry(
                            "redis",
                            List.of(
                                    "infrastructureReadSuccess",
                                    "infrastructureAuthSuccess",
                                    "redisPingSuccess",
                                    "redisClients",
                                    "redisUsedMemory")),
                    Map.entry(
                            "elasticsearch",
                            List.of(
                                    "infrastructureReadSuccess",
                                    "infrastructureAuthSuccess",
                                    "elasticClusterStatus",
                                    "elasticNodes",
                                    "elasticSearchSuccess")),
                    Map.entry(
                            "prometheus",
                            List.of(
                                    "infrastructureReadSuccess",
                                    "prometheusReady",
                                    "prometheusTimeSeries",
                                    "prometheusFailedTargets")),
                    Map.entry(
                            "alertmanager",
                            List.of(
                                    "infrastructureReadSuccess",
                                    "alertmanagerReady",
                                    "alertmanagerConfigLoaded",
                                    "alertmanagerAlerts")),
                    Map.entry(
                            "grafana",
                            List.of("infrastructureReadSuccess", "grafanaDatabaseReady")),
                    Map.entry(
                            "sentinel",
                            List.of("infrastructureReadSuccess", "sentinelConsoleReady")),
                    Map.entry(
                            "qdrant",
                            List.of(
                                    "infrastructureReadSuccess",
                                    "qdrantReady",
                                    "qdrantCollections")),
                    Map.entry(
                            "rabbitmq",
                            List.of(
                                    "connections",
                                    "consumers",
                                    "messagesReady",
                                    "messagesUnacked")),
                    Map.entry(
                            "nacos",
                            List.of(
                                    "registeredServices",
                                    "registeredInstances",
                                    "configurationCount")));
    private final Map<String, String> environments = new HashMap<>();
    private final Set<String> targets;
    private final long windowSeconds;
    private final int successes;
    private final long sampleInterval;
    private final long maximumAge;

    EventRecoveryRules(
            @Value("${ops.event.recovery.environment-mapping:}") String environmentMapping,
            @Value(
                            "${ops.event.recovery.allowed-targets:ops-gateway,ops-auth-service,"
                                    + "ops-ticket-service,ops-knowledge-service,ops-rag-service,"
                                    + "ops-platform-service,ops-agent-service,mysql,redis,"
                                    + "elasticsearch,prometheus,alertmanager,rabbitmq,nacos,"
                                    + "grafana,sentinel,qdrant}")
                    String allowedTargets,
            @Value("${ops.event.recovery.observation-window-seconds:180}") long windowSeconds,
            @Value("${ops.event.recovery.minimum-successes:5}") int successes,
            @Value("${ops.event.recovery.sample-interval-seconds:30}") long sampleInterval,
            @Value("${ops.event.recovery.sample-max-age-seconds:90}") long maximumAge) {
        for (String entry : environmentMapping.split(",")) {
            if (entry.isBlank()) continue;
            String[] pair = entry.trim().split("=", -1);
            if (pair.length != 2
                    || !pair[0].matches("[A-Z0-9_-]{1,32}")
                    || !pair[1].matches("[A-Z0-9_-]{1,32}")
                    || environments.putIfAbsent(pair[0], pair[1]) != null)
                throw new IllegalArgumentException("恢复环境映射必须是唯一的源环境=观测环境");
        }
        targets =
                new HashSet<>(
                        Arrays.stream(allowedTargets.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isBlank())
                                .toList());
        if (windowSeconds < 1
                || windowSeconds > 86400
                || successes < 2
                || successes > 50
                || sampleInterval < 1
                || sampleInterval > 3600
                || maximumAge < sampleInterval
                || maximumAge > 3600) throw new IllegalArgumentException("恢复观察参数不在有效范围");
        this.windowSeconds = windowSeconds;
        this.successes = successes;
        this.sampleInterval = sampleInterval;
        this.maximumAge = maximumAge;
    }

    String environment(String original, String target) {
        if (original == null
                || !environments.containsKey(original)
                || target == null
                || !targets.contains(target)
                || !JAVA_TARGETS.contains(target) && !NATIVE_TARGETS.containsKey(target))
            throw new BusinessException(ErrorCode.CONFLICT, "该事件尚未绑定明确的恢复目标或环境，请先完善服务关联与恢复规则");
        return environments.get(original);
    }

    List<String> availableTargets() {
        return targets.stream()
                .filter(
                        target ->
                                JAVA_TARGETS.contains(target) || NATIVE_TARGETS.containsKey(target))
                .sorted()
                .toList();
    }

    Map<String, String> availableEnvironments() {
        return Map.copyOf(environments);
    }

    List<String> bindingBlockers(String original, String target) {
        List<String> blockers = new ArrayList<>();
        if (target == null || target.isBlank()) blockers.add("缺少关联服务，请选择实际受影响的服务");
        else if (!availableTargets().contains(target))
            blockers.add("当前关联标识“" + target + "”未命中恢复目标；请核对告警的规范 CI 标签与已配置规则，采集 job 名称不等于服务标识");
        if (original == null || original.isBlank()) blockers.add("缺少事件环境，请选择已配置的环境");
        else if (!environments.containsKey(original)) blockers.add("事件环境尚未配置观测映射：" + original);
        return blockers;
    }

    String summary() {
        return "稳定观察至少 "
                + windowSeconds
                + " 秒、至少 "
                + successes
                + " 个不同成功样本，采样间隔 "
                + sampleInterval
                + " 秒；失败、缺样或采集异常重新累计";
    }

    String blocker(
            JsonNode data, String target, String environment, Instant resultAt, Instant now) {
        try {
            long ticketId = data.path("ticketId").asLong(-1);
            if (ticketId <= 0) return "恢复证据缺少实际工单身份";
            if (!target.equals(data.path("targetCode").asText())
                    || !environment.equals(data.path("environment").asText())
                    || !"CURRENT".equals(data.path("scope").asText())
                    || !fresh(time(data, "generatedAt"), now, 20)
                    || !data.path("alertsAvailable").asBoolean()) return "当前恢复证据身份、时间或告警来源不可确认";
            if (!validNode(data.path("currentNode"), target, environment, now))
                return "当前组件检查、采集或告警尚未达到技术恢复基线；业务确认另行记录";
            List<JsonNode> rows = new ArrayList<>();
            data.path("inspections")
                    .forEach(
                            row -> {
                                if (target.equals(row.path("ciCode").asText())
                                        && environment.equals(row.path("environment").asText()))
                                    rows.add(row);
                            });
            rows.sort(
                    Comparator.comparing((JsonNode row) -> time(row, "lastCheckedAt")).reversed());
            Set<String> runs = new HashSet<>();
            Instant newest = null;
            Instant oldest = null;
            int count = 0;
            for (JsonNode row : rows) {
                Instant finished = time(row, "lastCheckedAt");
                if (row.path("ticketId").asLong(-1) != ticketId)
                    return "恢复观察记录属于另一张工单，不能借用其他事件的恢复证据";
                if (finished.isBefore(resultAt)) break;
                if (!"COMPLETED".equals(row.path("executionStatus").asText())
                        || !"PASS".equals(row.path("result").asText())
                        || row.path("runId").asText().isBlank()
                        || !runs.add(row.path("runId").asText())
                        || !validNode(row.path("evidence"), target, environment, finished))
                    return "观察期间存在失败、缺失或不完整巡检，需从新一轮有效检查重新累计";
                Instant source = time(row.path("evidence"), "observedAt");
                if (source.isBefore(resultAt) || source.isAfter(now.plusSeconds(2))) break;
                if (newest == null) {
                    if (!fresh(source, now, maximumAge)) return "最近完整巡检的真实样本已过期，请重新检查";
                    newest = source;
                } else {
                    long gap = Duration.between(source, oldest).getSeconds();
                    if (gap <= 0)
                        continue; // Repeated snapshots of the same scrape do not add success.
                    if (gap > maximumAge) return "恢复观察存在断采间隔，连续成功计数需要重新开始";
                    if (gap < sampleInterval) continue;
                }
                oldest = source;
                count++;
                if (count >= successes
                        && Duration.between(oldest, newest).getSeconds() >= windowSeconds)
                    return "";
            }
            return "技术恢复需在本次处理结果之后持续观察至少"
                    + windowSeconds
                    + "秒，并取得至少"
                    + successes
                    + "次完整且独立的新鲜巡检；重复点击或人工说明不增加机器证据";
        } catch (RuntimeException invalid) {
            return "恢复证据缺少可核对的时间、实例或指标，不能确认技术恢复";
        }
    }

    private boolean validNode(JsonNode node, String target, String environment, Instant checkedAt) {
        if (!target.equals(node.path("ciCode").asText())
                || !environment.equals(node.path("environment").asText())
                || !target.equals(node.path("identity").path("ciCode").asText())
                || !environment.equals(node.path("identity").path("environment").asText())
                || !"HEALTHY".equals(node.path("health").asText())
                || !"READY".equals(node.path("observation").path("status").asText())
                || !node.path("activeAlertCount").isNumber()
                || node.path("activeAlertCount").asInt() != 0
                || !fresh(time(node, "observedAt"), checkedAt, maximumAge)) return false;
        List<String> metrics =
                JAVA_TARGETS.contains(target)
                        ? List.of("cpuUsage", "memoryUsage")
                        : NATIVE_TARGETS.get(target);
        if (metrics == null) return false;
        for (String key : metrics) {
            JsonNode metric = node.path("metricEvidence").path(key);
            if (!metric.path("value").isNumber()
                    || !Double.isFinite(metric.path("value").asDouble())
                    || !"OBSERVED".equals(metric.path("reasonCode").asText())
                    || !Set.of("JVM_RUNTIME", "NATIVE_METRICS")
                            .contains(metric.path("scope").asText())
                    || !fresh(time(metric, "sampledAt"), checkedAt, maximumAge)) return false;
        }
        return true;
    }

    private static Instant time(JsonNode node, String key) {
        return Instant.parse(node.path(key).asText());
    }

    private static boolean fresh(Instant sample, Instant now, long age) {
        return sample.isAfter(now.minusSeconds(age)) && !sample.isAfter(now.plusSeconds(2));
    }
}
