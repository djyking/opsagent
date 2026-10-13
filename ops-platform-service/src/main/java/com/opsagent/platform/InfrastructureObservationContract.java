package com.opsagent.platform;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only infrastructure checks retain their source time and never promote scrape-only health.
 *
 * @author heyu
 * @since 2026/9/3
 */
final class InfrastructureObservationContract {
    private static final Set<String> KINDS =
            Set.of(
                    "mysql",
                    "redis",
                    "rabbitmq-amqp",
                    "windows-host",
                    "linux-host",
                    "elasticsearch",
                    "prometheus",
                    "alertmanager",
                    "grafana",
                    "sentinel",
                    "qdrant");
    private static final Map<String, List<String>> REQUIRED =
            Map.of(
                    "mysql",
                            List.of("mysqlQuerySuccess", "mysqlConnections", "mysqlMaxConnections"),
                    "redis", List.of("redisPingSuccess", "redisClients", "redisUsedMemory"),
                    "rabbitmq-amqp",
                            List.of(
                                    "rabbitmqQueueReadSuccess",
                                    "rabbitmqQueueMessages",
                                    "rabbitmqQueueConsumers"),
                    "windows-host",
                            List.of(
                                    "hostCpuUsage",
                                    "hostMemoryUsage",
                                    "hostDiskUsage",
                                    "hostNetworkReceiveRate",
                                    "hostNetworkTransmitRate",
                                    "hostReadComplete"),
                    "elasticsearch",
                            List.of("elasticClusterStatus", "elasticNodes", "elasticSearchSuccess"),
                    "prometheus",
                            List.of(
                                    "prometheusReady",
                                    "prometheusTimeSeries",
                                    "prometheusFailedTargets"),
                    "alertmanager",
                            List.of(
                                    "alertmanagerReady",
                                    "alertmanagerConfigLoaded",
                                    "alertmanagerAlerts"),
                    "grafana", List.of("grafanaDatabaseReady"),
                    "sentinel", List.of("sentinelConsoleReady"),
                    "qdrant", List.of("qdrantReady", "qdrantCollections"));
    private static final Map<String, String> METRICS = metrics();

    private InfrastructureObservationContract() {}

    static String kind(String job) {
        if ("opsagent-isolated-demo-redis".equals(job)) return "redis";
        if ("opsagent-isolated-demo-rabbitmq".equals(job)) return "rabbitmq-amqp";
        String kind = job.startsWith("opsagent-") ? job.substring(9) : "";
        return KINDS.contains(kind) ? kind : "";
    }

    static Set<String> required(String job) {
        if (kind(job).isBlank()) return Set.of();
        var required =
                new java.util.LinkedHashSet<>(
                        REQUIRED.get("linux-host".equals(kind(job)) ? "windows-host" : kind(job)));
        required.addAll(List.of("infrastructureReadSuccess", "infrastructureAuthSuccess"));
        return Set.copyOf(required);
    }

    static void addEvidence(
            Map<String, ObservabilityDtos.MetricEvidence> result,
            PrometheusAdapter.Snapshot snapshot,
            Instant now,
            long age) {
        String job =
                snapshot.targets().stream()
                        .map(t -> t.label("job"))
                        .filter(j -> !kind(j).isBlank())
                        .findFirst()
                        .orElse("");
        if (job.isBlank()) return;
        var raw = snapshot.series().getOrDefault("raw", List.of());
        for (String key : required(job).stream().sorted().toList()) {
            String metric = METRICS.get(key);
            var values = raw.stream().filter(s -> metric.equals(s.label("__name__"))).toList();
            Instant at = null;
            boolean complete = !values.isEmpty();
            for (var value : values) {
                var time =
                        raw.stream()
                                .filter(
                                        t ->
                                                "opsagent_infra_check_timestamp_seconds"
                                                                .equals(t.label("__name__"))
                                                        && t.label("instance")
                                                                .equals(value.label("instance")))
                                .findFirst()
                                .orElse(null);
                Instant checked = time == null ? null : timestamp(time.value());
                complete &=
                        NodeObservationService.fresh(value.observedAt(), now, age)
                                && time != null
                                && NodeObservationService.fresh(time.observedAt(), now, age)
                                && NodeObservationService.fresh(checked, now, age);
                if (checked != null && (at == null || checked.isBefore(at))) at = checked;
            }
            // Every reporting target must supply this field. One instance cannot cover another.
            complete &=
                    snapshot.targets().stream()
                            .allMatch(
                                    t ->
                                            values.stream()
                                                    .anyMatch(
                                                            v ->
                                                                    t.label("instance")
                                                                            .equals(
                                                                                    v.label(
                                                                                            "instance"))));
            boolean minimum =
                    key.endsWith("Success") || key.endsWith("Ready") || key.endsWith("Loaded");
            Double value =
                    complete
                            ? minimum
                                    ? values.stream()
                                            .mapToDouble(PrometheusAdapter.Sample::value)
                                            .min()
                                            .orElseThrow()
                                    : values.stream()
                                            .mapToDouble(PrometheusAdapter.Sample::value)
                                            .max()
                                            .orElseThrow()
                            : null;
            result.put(
                    key,
                    new ObservabilityDtos.MetricEvidence(
                            value,
                            unit(key),
                            null,
                            at,
                            value != null
                                    ? "OBSERVED"
                                    : at == null
                                            ? "METRIC_NOT_EXPOSED"
                                            : "STALE_OR_INCOMPLETE_CHECK",
                            "NATIVE_METRICS",
                            minimum
                                    ? "MIN_ACROSS_REPORTING_INSTANCES"
                                    : "MAX_ACROSS_REPORTING_INSTANCES"));
        }
    }

    static String error(PrometheusAdapter.Snapshot snapshot, Instant now, long age) {
        return snapshot.series().getOrDefault("raw", List.of()).stream()
                .filter(
                        s ->
                                "opsagent_infra_check_error".equals(s.label("__name__"))
                                        && s.value() == 1
                                        && NodeObservationService.fresh(s.observedAt(), now, age))
                .map(s -> s.label("reason"))
                .filter(
                        Set.of(
                                        "AUTH_REJECTED",
                                        "CONNECTION_REFUSED",
                                        "TIMEOUT",
                                        "INVALID_RESPONSE",
                                        "DEPENDENCY_MISSING",
                                        "CHECK_FAILED")
                                ::contains)
                .findFirst()
                .orElse("CHECK_FAILED");
    }

    static String issue(String job, Map<String, Object> metrics) {
        if (kind(job).isBlank()) return "";
        if (zero(metrics.get("infrastructureAuthSuccess"))) return "INFRA_AUTH_REJECTED";
        if (zero(metrics.get("infrastructureReadSuccess"))) return "INFRA_READ_FAILED";
        if (required(job).stream().anyMatch(key -> !(metrics.get(key) instanceof Number)))
            return "INFRA_EVIDENCE_INCOMPLETE";
        if (List.of(
                        "mysqlQuerySuccess",
                        "redisPingSuccess",
                        "rabbitmqQueueReadSuccess",
                        "elasticSearchSuccess",
                        "prometheusReady",
                        "alertmanagerReady",
                        "alertmanagerConfigLoaded",
                        "grafanaDatabaseReady",
                        "sentinelConsoleReady",
                        "qdrantReady")
                .stream()
                .anyMatch(key -> zero(metrics.get(key)))) return "INFRA_CHECK_FAILED";
        if (number(metrics.get("elasticClusterStatus")) >= 2) return "ELASTIC_CLUSTER_RED";
        if (number(metrics.get("elasticClusterStatus")) == 1) return "ELASTIC_CLUSTER_YELLOW";
        if (number(metrics.get("prometheusFailedTargets")) > 0) return "DOWNSTREAM_SCRAPE_FAILED";
        boolean host = Set.of("windows-host", "linux-host").contains(kind(job));
        if (host && zero(metrics.get("hostReadComplete"))) return "INFRA_EVIDENCE_INCOMPLETE";
        if (host
                && List.of("hostCpuUsage", "hostMemoryUsage", "hostDiskUsage").stream()
                        .anyMatch(key -> number(metrics.get(key)) >= 90))
            return "HOST_RESOURCE_PRESSURE";
        if (host) return "HOST_RESOURCE_OBSERVED";
        return "INFRA_READ_VERIFIED";
    }

    static String message(String issue) {
        return switch (issue) {
            case "INFRA_AUTH_REJECTED" -> "只读检查鉴权被拒绝；不能据此认定组件宕机";
            case "INFRA_READ_FAILED" -> "只读检查未成功；核对连接、超时与检查记录";
            case "INFRA_EVIDENCE_INCOMPLETE" -> "缺少新鲜完整的组件检查证据；采集在线不代表检查通过";
            case "INFRA_CHECK_FAILED", "ELASTIC_CLUSTER_RED" -> "组件只读检查返回明确失败；查看原生指标定位影响";
            case "ELASTIC_CLUSTER_YELLOW" -> "Elasticsearch 为 yellow，主分片可用但副本未齐；不等同搜索不可用";
            case "DOWNSTREAM_SCRAPE_FAILED" -> "Prometheus 自身就绪，但存在下游采集失败";
            case "HOST_RESOURCE_PRESSURE" -> "已观测的主机资源使用率达到90%，请结合实际负载核对；不代表业务已故障";
            case "HOST_RESOURCE_OBSERVED" -> "已取得当前宿主操作系统资源；不代表单个进程或容器状态";
            default -> "组件只读检查通过；未覆盖应用业务、通知送达或实际模型调用";
        };
    }

    private static boolean zero(Object value) {
        return value instanceof Number n && n.doubleValue() == 0;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : -1;
    }

    private static Instant timestamp(double value) {
        try {
            return Double.isFinite(value) ? Instant.ofEpochSecond((long) value) : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String unit(String key) {
        if (key.startsWith("host") && key.endsWith("Usage")) return "%";
        if (key.startsWith("hostNetwork")) return "bytes/s";
        if (key.equals("hostReadComplete")) return "boolean";
        if (key.endsWith("Success") || key.endsWith("Ready") || key.endsWith("Loaded"))
            return "boolean";
        if (key.endsWith("Memory")) return "bytes";
        if (key.equals("elasticClusterStatus")) return "0=green,1=yellow,2=red";
        return "count";
    }

    private static Map<String, String> metrics() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("infrastructureReadSuccess", "opsagent_infra_read_success");
        values.put("infrastructureAuthSuccess", "opsagent_infra_auth_success");
        values.put("mysqlQuerySuccess", "opsagent_infra_mysql_query_success");
        values.put("mysqlConnections", "opsagent_infra_mysql_connections");
        values.put("mysqlMaxConnections", "opsagent_infra_mysql_max_connections");
        values.put("redisPingSuccess", "opsagent_infra_redis_ping_success");
        values.put("redisClients", "opsagent_infra_redis_clients");
        values.put("redisUsedMemory", "opsagent_infra_redis_used_memory");
        values.put("rabbitmqQueueReadSuccess", "opsagent_infra_rabbitmq_queue_read_success");
        values.put("rabbitmqQueueMessages", "opsagent_infra_rabbitmq_queue_messages");
        values.put("rabbitmqQueueConsumers", "opsagent_infra_rabbitmq_queue_consumers");
        values.put("hostCpuUsage", "opsagent_infra_host_cpu_usage_percent");
        values.put("hostMemoryUsage", "opsagent_infra_host_physical_memory_usage_percent");
        values.put("hostDiskUsage", "opsagent_infra_host_disk_usage_percent");
        values.put(
                "hostNetworkReceiveRate", "opsagent_infra_host_network_receive_bytes_per_second");
        values.put(
                "hostNetworkTransmitRate", "opsagent_infra_host_network_transmit_bytes_per_second");
        values.put("hostReadComplete", "opsagent_infra_host_complete");
        values.put("elasticClusterStatus", "opsagent_infra_elasticsearch_cluster_status");
        values.put("elasticNodes", "opsagent_infra_elasticsearch_nodes");
        values.put("elasticSearchSuccess", "opsagent_infra_elasticsearch_search_success");
        values.put("prometheusReady", "opsagent_infra_prometheus_ready");
        values.put("prometheusTimeSeries", "opsagent_infra_prometheus_time_series");
        values.put("prometheusFailedTargets", "opsagent_infra_prometheus_failed_targets");
        values.put("alertmanagerReady", "opsagent_infra_alertmanager_ready");
        values.put("alertmanagerConfigLoaded", "opsagent_infra_alertmanager_config_loaded");
        values.put("alertmanagerAlerts", "opsagent_infra_alertmanager_alerts");
        values.put("grafanaDatabaseReady", "opsagent_infra_grafana_database_ready");
        values.put("sentinelConsoleReady", "opsagent_infra_sentinel_console_ready");
        values.put("qdrantReady", "opsagent_infra_qdrant_ready");
        values.put("qdrantCollections", "opsagent_infra_qdrant_collections");
        return Map.copyOf(values);
    }
}
