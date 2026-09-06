package com.opsagent.platform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固定指标合同与采集诊断；采集失败不代表业务故障，身份不能跨环境串用。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class NodeObservationService {
    private static final Map<String, List<String>> DEPENDENCIES =
            Map.of(
                    "rps", List.of("http_server_requests_seconds_count"),
                    "errorRate", List.of("http_server_requests_seconds_count"),
                    "p95Ms", List.of("http_server_requests_seconds_bucket"),
                    "cpuUsage", List.of("process_cpu_usage"),
                    "memoryUsage", List.of("jvm_memory_used_bytes", "jvm_memory_max_bytes"));

    boolean matches(
            Map<String, String> labels,
            String job,
            String code,
            String environment,
            String namespace,
            String cluster,
            boolean legacy) {
        if (job.isBlank() || !job.equals(labels.getOrDefault("job", ""))) return false;
        String env = labels.getOrDefault("environment", "");
        if (env.isBlank() ? !legacy : !environment.equalsIgnoreCase(env)) return false;
        String ci = labels.getOrDefault("ci_code", "");
        // Demo notification shares its JVM scrape with order, but its business probe is separate.
        if (!ci.isBlank()
                && !ci.equals(code)
                && !("ops-demo-notification-service".equals(code)
                        && "ops-demo-order-service".equals(ci))) {
            return false;
        }
        return (namespace.isBlank() || namespace.equals(labels.getOrDefault("namespace", "")))
                && (cluster.isBlank() || cluster.equals(labels.getOrDefault("cluster", "")));
    }

    PrometheusAdapter.Snapshot scoped(
            PrometheusAdapter.Snapshot snapshot,
            String job,
            String code,
            String environment,
            String namespace,
            String cluster,
            boolean legacy) {
        // After relabeling, old series remain in Prometheus lookback/rate windows.
        // An explicit current target binds this scope; old unlabeled samples cannot join it.
        boolean explicitIdentity =
                snapshot.targets().stream()
                        .anyMatch(
                                target ->
                                        !target.label("ci_code").isBlank()
                                                && !target.label("environment").isBlank()
                                                && matches(
                                                        target.labels(),
                                                        job,
                                                        code,
                                                        environment,
                                                        namespace,
                                                        cluster,
                                                        false));
        boolean allowLegacy = legacy && !explicitIdentity;
        Map<String, List<PrometheusAdapter.Sample>> series = new LinkedHashMap<>();
        for (var entry : snapshot.series().entrySet()) {
            boolean probe = "probe".equals(entry.getKey()) || "probeAt".equals(entry.getKey());
            series.put(
                    entry.getKey(),
                    entry.getValue().stream()
                            .filter(
                                    sample -> {
                                        if (probe)
                                            return "DEMO".equals(environment)
                                                    && code.equals(sample.service())
                                                    && trustedProbePublisher(sample);
                                        Map<String, String> labels =
                                                new LinkedHashMap<>(sample.labels());
                                        labels.put("job", sample.job());
                                        if (explicitIdentity
                                                && labels.getOrDefault("ci_code", "").isBlank())
                                            return false;
                                        return matches(
                                                labels,
                                                job,
                                                code,
                                                environment,
                                                namespace,
                                                cluster,
                                                allowLegacy);
                                    })
                            .toList());
        }
        List<PrometheusAdapter.Target> targets =
                snapshot.targets().stream()
                        .filter(
                                t ->
                                        (!explicitIdentity || !t.label("ci_code").isBlank())
                                                && matches(
                                                        t.labels(),
                                                        job,
                                                        code,
                                                        environment,
                                                        namespace,
                                                        cluster,
                                                        allowLegacy))
                        .toList();
        Map<String, String> errors = new LinkedHashMap<>(snapshot.errors());
        if (targets.isEmpty()
                && snapshot.targets().stream().anyMatch(t -> job.equals(t.label("job")))) {
            errors.put("binding", "IDENTITY_MAPPING_MISSING");
        }
        if (targets.stream()
                        .map(t -> t.label("namespace") + "/" + t.label("cluster"))
                        .distinct()
                        .count()
                > 1) {
            errors.put("binding", "AMBIGUOUS_SCOPE");
            series.replaceAll((key, value) -> List.of());
        }
        return new PrometheusAdapter.Snapshot(
                snapshot.healthy(),
                snapshot.message(),
                snapshot.checkedAt(),
                series,
                targets,
                errors);
    }

    private boolean trustedProbePublisher(PrometheusAdapter.Sample sample) {
        return "opsagent-platform".equals(sample.job())
                && (sample.label("environment").isBlank()
                        || "PROD".equals(sample.label("environment")))
                && (sample.label("ci_code").isBlank()
                        || "ops-platform-service".equals(sample.label("ci_code")));
    }

    Map<String, ObservabilityDtos.MetricEvidence> metricEvidence(
            PrometheusAdapter.Snapshot snapshot, String range, Instant now, long age) {
        Map<String, ObservabilityDtos.MetricEvidence> result = new LinkedHashMap<>();
        for (String metric : List.of("rps", "errorRate", "p95Ms", "cpuUsage", "memoryUsage")) {
            List<PrometheusAdapter.Sample> selected =
                    snapshot.series().getOrDefault(metric, List.of());
            List<String> dependencies = DEPENDENCIES.get(metric);
            Instant at = null;
            boolean complete = true;
            for (String dependency : dependencies) {
                Instant found =
                        raw(snapshot, dependency, "").stream()
                                .map(PrometheusAdapter.Sample::observedAt)
                                .min(Instant::compareTo)
                                .orElse(null);
                if (!fresh(found, now, age)) complete = false;
                if (found != null && (at == null || found.isBefore(at))) at = found;
            }
            Double value =
                    selected.size() == 1
                                    && complete
                                    && fresh(selected.get(0).observedAt(), now, age)
                            ? selected.get(0).value()
                            : null;
            String reason =
                    value != null
                            ? "OBSERVED"
                            : at == null
                                    ? "METRIC_NOT_EXPOSED"
                                    : !complete
                                            ? "STALE_SAMPLE"
                                            : selected.size() > 1
                                                    ? "AMBIGUOUS_SCOPE"
                                                    : "NO_WINDOW_DATA";
            String unit = "rps".equals(metric) ? "requests/s" : "p95Ms".equals(metric) ? "ms" : "%";
            result.put(
                    metric,
                    new ObservabilityDtos.MetricEvidence(
                            value,
                            unit,
                            Set.of("rps", "errorRate", "p95Ms").contains(metric)
                                    ? seconds(range)
                                    : null,
                            at,
                            reason,
                            Set.of("cpuUsage", "memoryUsage").contains(metric)
                                    ? "JVM_RUNTIME"
                                    : "REQUEST_WINDOW"));
        }
        addNative(
                result,
                snapshot,
                now,
                age,
                "connections",
                "rabbitmq_connections",
                "",
                "connections");
        addNative(
                result,
                snapshot,
                now,
                age,
                "consumers",
                "rabbitmq_channel_consumers",
                "",
                "consumers");
        addNative(
                result,
                snapshot,
                now,
                age,
                "messagesReady",
                "rabbitmq_queue_messages_ready",
                "",
                "messages");
        addNative(
                result,
                snapshot,
                now,
                age,
                "messagesUnacked",
                "rabbitmq_queue_messages_unacked",
                "",
                "messages");
        addNative(
                result,
                snapshot,
                now,
                age,
                "memoryAlarm",
                "rabbitmq_alarms_memory_used_watermark",
                "",
                "boolean");
        addNative(
                result,
                snapshot,
                now,
                age,
                "diskAlarm",
                "rabbitmq_alarms_free_disk_space_watermark",
                "",
                "boolean");
        addNative(
                result,
                snapshot,
                now,
                age,
                "registeredServices",
                "nacos_monitor",
                "serviceCount",
                "services");
        addNative(
                result,
                snapshot,
                now,
                age,
                "registeredInstances",
                "nacos_monitor",
                "ipCount",
                "instances");
        addNative(
                result,
                snapshot,
                now,
                age,
                "configurationCount",
                "nacos_monitor",
                "configCount",
                "configurations");
        addNative(
                result, snapshot, now, age, "collections", "collections_total", "", "collections");
        addNative(result, snapshot, now, age, "vectors", "collections_vector_total", "", "vectors");
        addNative(
                result,
                snapshot,
                now,
                age,
                "recoveryMode",
                "app_status_recovery_mode",
                "",
                "boolean");
        addNative(
                result,
                snapshot,
                now,
                age,
                "timeSeries",
                "prometheus_tsdb_head_series",
                "",
                "series");
        addNative(result, snapshot, now, age, "sourceAlerts", "alertmanager_alerts", "", "alerts");
        return result;
    }

    private void addNative(
            Map<String, ObservabilityDtos.MetricEvidence> result,
            PrometheusAdapter.Snapshot snapshot,
            Instant now,
            long age,
            String key,
            String metric,
            String name,
            String unit) {
        List<PrometheusAdapter.Sample> samples = raw(snapshot, metric, name);
        // Do not add irrelevant adapter fields to every Java node.
        if (samples.isEmpty() && !applicable(snapshot, metric)) return;
        Instant at =
                samples.stream()
                        .map(PrometheusAdapter.Sample::observedAt)
                        .min(Instant::compareTo)
                        .orElse(null);
        boolean maximum =
                "boolean".equals(unit)
                        || "nacos_monitor".equals(metric)
                        || Set.of("collections_total", "collections_vector_total").contains(metric);
        Double value =
                !samples.isEmpty()
                                && samples.stream().allMatch(s -> fresh(s.observedAt(), now, age))
                        ? maximum
                                ? samples.stream()
                                        .mapToDouble(PrometheusAdapter.Sample::value)
                                        .max()
                                        .orElseThrow()
                                : samples.stream()
                                        .mapToDouble(PrometheusAdapter.Sample::value)
                                        .sum()
                        : null;
        result.put(
                key,
                new ObservabilityDtos.MetricEvidence(
                        value,
                        unit,
                        null,
                        at,
                        value != null
                                ? "OBSERVED"
                                : at == null ? "METRIC_NOT_EXPOSED" : "STALE_SAMPLE",
                        "NATIVE_METRICS",
                        maximum ? "MAX_ACROSS_REPORTING_INSTANCES" : "SUM_ACROSS_INSTANCES"));
    }

    private boolean applicable(PrometheusAdapter.Snapshot snapshot, String metric) {
        return snapshot.targets().stream()
                .anyMatch(
                        target -> {
                            String job = target.label("job");
                            return job.contains("rabbitmq") && metric.startsWith("rabbitmq_")
                                    || job.contains("nacos") && metric.startsWith("nacos_")
                                    || job.contains("qdrant")
                                            && Set.of(
                                                            "collections_total",
                                                            "collections_vector_total",
                                                            "app_status_recovery_mode")
                                                    .contains(metric);
                        });
    }

    private List<PrometheusAdapter.Sample> raw(
            PrometheusAdapter.Snapshot snapshot, String metric, String name) {
        return snapshot.series().getOrDefault("raw", List.of()).stream()
                .filter(
                        s ->
                                metric.equals(s.label("__name__"))
                                        && (name.isBlank() || name.equals(s.label("name"))))
                .toList();
    }

    ObservabilityDtos.Observation observe(
            Map<String, Object> ci,
            String job,
            PrometheusAdapter.Snapshot snapshot,
            Map<String, ObservabilityDtos.MetricEvidence> evidence,
            Instant now,
            long age,
            Double probe,
            Instant probeAt) {
        List<PrometheusAdapter.Sample> up = snapshot.series().getOrDefault("up", List.of());
        List<PrometheusAdapter.Sample> freshUp =
                up.stream().filter(s -> fresh(s.observedAt(), now, age)).toList();
        List<ObservabilityDtos.ObservedInstance> instances = new ArrayList<>();
        for (PrometheusAdapter.Target target : snapshot.targets()) {
            PrometheusAdapter.Sample sample =
                    up.stream()
                            .filter(s -> target.label("instance").equals(s.label("instance")))
                            .findFirst()
                            .orElse(null);
            instances.add(
                    new ObservabilityDtos.ObservedInstance(
                            identity(
                                    ci,
                                    target.label("instance"),
                                    target.label("namespace"),
                                    target.label("cluster")),
                            job,
                            target.label("instance"),
                            target.endpoint(),
                            target.health(),
                            target.error(),
                            target.lastScrape(),
                            sample == null ? null : sample.observedAt(),
                            sample == null ? null : sample.lastSuccessfulAt(),
                            sample != null && fresh(sample.observedAt(), now, age)
                                    ? sample.value()
                                    : null,
                            "PROMETHEUS_TARGET"));
        }
        Instant sampledAt =
                up.stream()
                        .map(PrometheusAdapter.Sample::observedAt)
                        .min(Instant::compareTo)
                        .orElseGet(
                                () ->
                                        snapshot.targets().stream()
                                                .map(PrometheusAdapter.Target::lastScrape)
                                                .filter(java.util.Objects::nonNull)
                                                .min(Instant::compareTo)
                                                .orElse(null));
        Instant successfulAt =
                up.stream()
                        .map(PrometheusAdapter.Sample::lastSuccessfulAt)
                        .filter(java.util.Objects::nonNull)
                        .min(Instant::compareTo)
                        .orElse(null);
        long successful = freshUp.stream().filter(s -> s.value() == 1).count();
        String status;
        String reason;
        String message;
        if (job.isBlank()) {
            boolean unsupported = "EXTERNAL_API".equals(ci.get("ciType"));
            status = unsupported ? "UNSUPPORTED" : "NOT_CONFIGURED";
            reason = unsupported ? "ADAPTER_UNSUPPORTED" : "EXPORTER_NOT_CONFIGURED";
            message = unsupported ? "没有被动观测适配器；刷新不会发起付费模型调用" : "未配置指标导出器或采集绑定";
        } else if (snapshot.errors().containsKey("up")) {
            status = "FAILED";
            reason = "SOURCE_UNAVAILABLE";
            message = "Prometheus 查询失败；无法据此判断业务是否正常";
        } else if (snapshot.errors().containsKey("binding")) {
            status = "NOT_CONFIGURED";
            reason = snapshot.errors().get("binding");
            message = "采集存在但CI、环境或命名空间映射缺失或不唯一；不会跨身份合并指标";
        } else if (freshUp.isEmpty() && sampledAt != null && !fresh(sampledAt, now, age)) {
            status = "STALE";
            reason = "STALE_SAMPLE";
            message = "最近采集证据已过期；刷新时间不续期样本";
        } else if (freshUp.isEmpty() && snapshot.targets().isEmpty()) {
            status = "NOT_CONFIGURED";
            reason = "TARGET_NOT_CONFIGURED";
            message = "已识别适配器，但没有匹配环境和CI的采集目标";
        } else if (freshUp.isEmpty()) {
            status = "NO_DATA";
            reason = "NO_FRESH_SAMPLE";
            message = "已配置目标，尚无有效的新鲜采集样本";
        } else if (successful == 0) {
            status = "FAILED";
            reason = scrapeReason(snapshot.targets());
            message = "目标指标抓取失败；这不等于业务服务宕机，请核对实例抓取错误";
        } else if (successful < freshUp.size()
                || successful < snapshot.targets().size()
                || !snapshot.errors().isEmpty()
                || required(job).isEmpty()
                || required(job).stream()
                        .anyMatch(
                                key ->
                                        !evidence.containsKey(key)
                                                || evidence.get(key).value() == null)) {
            status = "PARTIAL";
            reason = successful < freshUp.size() ? "PARTIAL_SCRAPE_FAILED" : "PARTIAL_METRICS";
            message = "仅部分观测可用；缺失指标与未覆盖的业务范围保持未知";
        } else {
            status = "READY";
            reason = "OBSERVED_SCOPE_READY";
            message = "已接入当前适配器观测范围；不代表全部业务健康";
        }
        List<ObservabilityDtos.ObservationCheck> checks =
                List.of(
                        new ObservabilityDtos.ObservationCheck(
                                "binding",
                                job.isBlank() ? "NOT_CONFIGURED" : "READY",
                                job.isBlank() ? "BINDING_MISSING" : "BINDING_RESOLVED",
                                "CI与环境固定绑定"),
                        new ObservabilityDtos.ObservationCheck(
                                "prometheus-query",
                                snapshot.errors().containsKey("up")
                                        ? "FAILED"
                                        : snapshot.errors().isEmpty() ? "READY" : "PARTIAL",
                                snapshot.errors().isEmpty()
                                        ? "QUERY_COMPLETED"
                                        : "QUERY_UNAVAILABLE",
                                "查询固定指标合同"),
                        new ObservabilityDtos.ObservationCheck(
                                "target-scrape", status, reason, message),
                        new ObservabilityDtos.ObservationCheck(
                                "business-probe",
                                probe == null
                                        ? "NOT_RUN"
                                        : !fresh(probeAt, now, 30)
                                                ? "STALE"
                                                : probe == 1 ? "READY" : "FAILED",
                                probe == null
                                        ? "BUSINESS_EVIDENCE_MISSING"
                                        : "INDEPENDENT_BUSINESS_PROBE",
                                "业务探针独立于采集"));
        return new ObservabilityDtos.Observation(
                status,
                reason,
                message,
                sampledAt,
                snapshot.checkedAt(),
                successfulAt,
                age,
                checks,
                List.copyOf(instances));
    }

    private Set<String> required(String job) {
        if (job.contains("rabbitmq"))
            return Set.of("connections", "consumers", "messagesReady", "messagesUnacked");
        if (job.contains("nacos"))
            return Set.of("cpuUsage", "memoryUsage", "registeredServices", "configurationCount");
        if (job.contains("qdrant")) return Set.of("collections", "recoveryMode");
        if (job.equals("opsagent-prometheus")) return Set.of("timeSeries");
        if (job.equals("opsagent-alertmanager")) return Set.of("sourceAlerts");
        if (job.startsWith("opsagent-")) return Set.of("cpuUsage", "memoryUsage");
        return Set.of();
    }

    /**
     * @author heyu
     */
    record Probe(Double value, Instant observedAt) {}

    Probe probe(PrometheusAdapter.Snapshot snapshot, Instant now, long age) {
        if (snapshot.errors().containsKey("probe") || snapshot.errors().containsKey("probeAt")) {
            return new Probe(null, null);
        }
        List<Probe> probes = new ArrayList<>();
        for (var value : snapshot.series().getOrDefault("probe", List.of())) {
            var time =
                    snapshot.series().getOrDefault("probeAt", List.of()).stream()
                            .filter(at -> probeIdentity(at).equals(probeIdentity(value)))
                            .findFirst()
                            .orElse(null);
            if (time == null
                    || !fresh(value.observedAt(), now, age)
                    || !fresh(time.observedAt(), now, age)
                    || (value.value() != 0 && value.value() != 1)) return new Probe(null, null);
            try {
                probes.add(new Probe(value.value(), Instant.ofEpochSecond((long) time.value())));
            } catch (RuntimeException invalid) {
                return new Probe(null, null);
            }
        }
        if (probes.isEmpty()) return new Probe(null, null);
        var failed =
                probes.stream()
                        .filter(p -> p.value() == 0 && fresh(p.observedAt(), now, 30))
                        .min(java.util.Comparator.comparing(Probe::observedAt));
        if (failed.isPresent()) return failed.get();
        // The oldest instance evidence conservatively governs freshness; never pair different
        // publishers.
        return probes.stream().min(java.util.Comparator.comparing(Probe::observedAt)).orElseThrow();
    }

    private String probeIdentity(PrometheusAdapter.Sample sample) {
        return sample.job()
                + ":"
                + sample.service()
                + ":"
                + sample.label("environment")
                + ":"
                + sample.label("ci_code")
                + ":"
                + sample.label("namespace")
                + ":"
                + sample.label("cluster")
                + ":"
                + sample.label("instance");
    }

    private String scrapeReason(List<PrometheusAdapter.Target> targets) {
        String errors =
                targets.stream()
                        .map(PrometheusAdapter.Target::error)
                        .reduce("", (left, right) -> left + " " + right)
                        .toLowerCase(java.util.Locale.ROOT);
        if (errors.contains("401") || errors.contains("403")) return "TARGET_AUTH_REJECTED";
        if (errors.contains("404")) return "TARGET_PATH_NOT_FOUND";
        if (errors.contains("deadline") || errors.contains("timeout")) return "TARGET_TIMEOUT";
        if (errors.contains("connection refused")) return "TARGET_CONNECTION_REFUSED";
        return "SCRAPE_FAILED";
    }

    ObservabilityDtos.Identity identity(
            Map<String, Object> ci, String instance, String namespace, String cluster) {
        return new ObservabilityDtos.Identity(
                String.valueOf(ci.get("ciCode")),
                String.valueOf(ci.getOrDefault("environment", "UNKNOWN")),
                namespace,
                cluster,
                instance);
    }

    static boolean fresh(Instant value, Instant now, long age) {
        return value != null
                && value.isAfter(now.minusSeconds(Math.max(1, age)))
                && !value.isAfter(now.plusSeconds(15));
    }

    private int seconds(String range) {
        int number = Integer.parseInt(range.substring(0, range.length() - 1));
        return number * (range.endsWith("h") ? 3600 : 60);
    }
}
