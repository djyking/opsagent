package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 独立主机范围的真实时序；按CMDB身份和原始采样时间关联，不将进程指标当成主机指标。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class HostResourceService {
    static final String JOB = "opsagent-windows-host";
    static final String LINUX_JOB = "opsagent-linux-host";
    private static final Set<String> HOST_JOBS = Set.of(JOB, LINUX_JOB);
    private final MonitoringService monitoring;
    private final ItsmPlatformService cmdb;
    private final ObjectMapper json;
    private Snapshot cached;

    HostResourceService(MonitoringService monitoring, ItsmPlatformService cmdb, ObjectMapper json) {
        this.monitoring = monitoring;
        this.cmdb = cmdb;
        this.json = json;
    }

    record Point(Instant at, double value) {}

    record Metric(
            String key,
            String label,
            String dimension,
            String unit,
            Double value,
            Instant sampledAt,
            String status,
            List<Point> points) {}

    record RelatedService(String ciCode, String ciName, String environment) {}

    record Host(
            String ciCode,
            String ciName,
            String environment,
            String job,
            String status,
            Instant observedAt,
            String source,
            List<RelatedService> services,
            List<Metric> metrics) {}

    record Snapshot(Instant capturedAt, String scope, String message, List<Host> hosts) {}

    record Definition(String key, String label, String metric, String unit, String dimension) {}

    private static final List<Definition> DEFINITIONS =
            List.of(
                    new Definition("cpuUsage", "主机 CPU", "cpu_usage_percent", "percent", ""),
                    new Definition(
                            "physicalMemoryUsage",
                            "物理内存",
                            "physical_memory_usage_percent",
                            "percent",
                            ""),
                    new Definition(
                            "physicalMemoryUsed",
                            "已用物理内存",
                            "physical_memory_used_bytes",
                            "bytes",
                            ""),
                    new Definition(
                            "physicalMemoryTotal",
                            "物理内存总量",
                            "physical_memory_total_bytes",
                            "bytes",
                            ""),
                    new Definition("diskUsage", "磁盘使用率", "disk_usage_percent", "percent", "disk"),
                    new Definition("diskFree", "磁盘可用空间", "disk_free_bytes", "bytes", "disk"),
                    new Definition("diskTotal", "磁盘容量", "disk_total_bytes", "bytes", "disk"),
                    new Definition(
                            "networkReceiveRate",
                            "网络接收",
                            "network_receive_bytes_per_second",
                            "bytes/s",
                            "interface"),
                    new Definition(
                            "networkTransmitRate",
                            "网络发送",
                            "network_transmit_bytes_per_second",
                            "bytes/s",
                            "interface"),
                    new Definition(
                            "networkUtilization",
                            "链路利用率",
                            "network_utilization_percent",
                            "percent",
                            "interface"),
                    new Definition(
                            "networkSpeed",
                            "网卡协商速率",
                            "network_speed_bits_per_second",
                            "bits/s",
                            "interface"),
                    new Definition("networkUp", "网卡连接", "network_up", "boolean", "interface"));

    synchronized Snapshot read(int requestedWindow, String selectedCi, boolean fresh) {
        int window = Math.max(15, Math.min(360, requestedWindow));
        Instant now = Instant.now();
        if (!fresh
                && window == 60
                && cached != null
                && cached.capturedAt().isAfter(now.minusSeconds(15)))
            return select(cached, selectedCi);
        List<Map<String, Object>> inventory = cmdb.cis(null, null);
        JsonNode matrix = json.createArrayNode();
        try {
            JsonNode response =
                    monitoring.queryRange(
                            "{job=~\""
                                    + JOB
                                    + "|"
                                    + LINUX_JOB
                                    + "\",__name__=~\"opsagent_infra_host_.+|"
                                    + "opsagent_infra_check_timestamp_seconds|opsagent_infra_read_success\"}",
                            now.minusSeconds(window * 60L),
                            now,
                            Math.max(15, window * 60 / 180));
            if ("success".equals(response.path("status").asText()))
                matrix = response.path("data").path("result");
        } catch (Exception unavailable) {
            // Registered hosts remain visible as unknown; do not manufacture healthy values.
        }
        List<Host> hosts = new ArrayList<>();
        for (Map<String, Object> ci : inventory) {
            JsonNode binding = json.valueToTree(ci.get("bindings"));
            String job = binding.path("prometheusJob").asText();
            if (!"HOST".equals(ci.get("ciType")) || !HOST_JOBS.contains(job)) continue;
            String code = String.valueOf(ci.get("ciCode"));
            String environment = String.valueOf(ci.get("environment"));
            List<Metric> metrics =
                    parse(matrix, code, environment, binding, now.minusSeconds(window * 60L), now);
            List<RelatedService> related =
                    inventory.stream()
                            .filter(
                                    row ->
                                            code.equals(
                                                    json.valueToTree(row.get("bindings"))
                                                            .path("hostCiCode")
                                                            .asText()))
                            .map(
                                    row ->
                                            new RelatedService(
                                                    String.valueOf(row.get("ciCode")),
                                                    String.valueOf(row.get("ciName")),
                                                    String.valueOf(row.get("environment"))))
                            .toList();
            Set<String> optional = Set.of("networkUtilization", "networkSpeed");
            boolean complete =
                    metrics.stream()
                            .filter(m -> !optional.contains(m.key()))
                            .allMatch(m -> "OBSERVED".equals(m.status()));
            boolean any = metrics.stream().anyMatch(m -> "OBSERVED".equals(m.status()));
            Instant observed =
                    metrics.stream()
                            .map(Metric::sampledAt)
                            .filter(java.util.Objects::nonNull)
                            .max(Instant::compareTo)
                            .orElse(null);
            hosts.add(
                    new Host(
                            code,
                            String.valueOf(ci.get("ciName")),
                            environment,
                            job,
                            complete ? "READY" : any ? "PARTIAL" : "UNKNOWN",
                            observed,
                            (LINUX_JOB.equals(job) ? "Linux" : "Windows")
                                    + " 宿主系统只读采集 / Prometheus；网络按指定网卡、磁盘按登记卷",
                            related,
                            metrics));
        }
        Snapshot result =
                new Snapshot(
                        now,
                        "HOST_RESOURCE",
                        hosts.isEmpty()
                                ? "尚未登记主机采集；进程/JVM 指标不代表主机资源"
                                : "当前宿主操作系统范围，包含其全部进程和容器；不代表单个容器。网卡利用率以实际协商速率为分母，缺少速率时不计算。",
                        hosts);
        if (window == 60) cached = result;
        return select(result, selectedCi);
    }

    private Snapshot select(Snapshot snapshot, String ci) {
        if (ci == null || ci.isBlank()) return snapshot;
        var hosts =
                snapshot.hosts().stream()
                        .filter(
                                host ->
                                        ci.equals(host.ciCode())
                                                || host.services().stream()
                                                        .anyMatch(
                                                                service ->
                                                                        ci.equals(
                                                                                service.ciCode())))
                        .toList();
        return new Snapshot(
                snapshot.capturedAt(),
                snapshot.scope(),
                hosts.isEmpty() ? "当前服务未登记宿主关联；不能用其他主机数据补齐" : snapshot.message(),
                hosts);
    }

    static List<Metric> parse(
            JsonNode matrix,
            String ci,
            String environment,
            JsonNode binding,
            Instant start,
            Instant now) {
        Map<String, JsonNode> series = new LinkedHashMap<>();
        String job = binding.path("prometheusJob").asText(JOB);
        if (!HOST_JOBS.contains(job)) return List.of();
        for (JsonNode row : matrix) {
            JsonNode label = row.path("metric");
            if (!job.equals(label.path("job").asText())
                    || !ci.equals(label.path("ci_code").asText())
                    || !environment.equals(label.path("environment").asText())
                    || label.has("host_id") && !ci.equals(label.path("host_id").asText())) continue;
            String key =
                    label.path("__name__").asText()
                            + "|"
                            + label.path("disk").asText()
                            + "|"
                            + label.path("interface").asText();
            // Duplicate target identities are ambiguous, never silently combine different machines.
            series.put(key, series.containsKey(key) ? null : row);
        }
        Map<Long, Double> checked = values(series.get("opsagent_infra_check_timestamp_seconds||"));
        Map<Long, Double> success = values(series.get("opsagent_infra_read_success||"));
        Long latestEvaluation =
                Stream.concat(checked.keySet().stream(), success.keySet().stream())
                        .max(Long::compareTo)
                        .orElse(null);
        List<Metric> result = new ArrayList<>();
        for (Definition definition : DEFINITIONS) {
            List<String> dimensions =
                    definition.dimension().isEmpty()
                            ? List.of("")
                            : Arrays.stream(
                                            binding.path(
                                                            definition.dimension().equals("disk")
                                                                    ? "hostDisks"
                                                                    : "hostInterfaces")
                                                    .asText()
                                                    .split(","))
                                    .map(String::trim)
                                    .filter(value -> !value.isBlank())
                                    .distinct()
                                    .limit(8)
                                    .toList();
            if (dimensions.isEmpty()) dimensions = List.of("未登记");
            for (String dimension : dimensions) {
                String key =
                        "opsagent_infra_host_"
                                + definition.metric()
                                + "|"
                                + (definition.dimension().equals("disk") ? dimension : "")
                                + "|"
                                + (definition.dimension().equals("interface") ? dimension : "");
                JsonNode raw = series.get(key);
                boolean correctScope =
                        raw != null
                                && (LINUX_JOB.equals(job) ? "LINUX_HOST" : "WINDOWS_HOST")
                                        .equals(raw.path("metric").path("scope").asText());
                TreeMap<Instant, Point> points = new TreeMap<>();
                Map<Long, Point> observations = new LinkedHashMap<>();
                if (correctScope)
                    values(raw)
                            .forEach(
                                    (evaluated, value) -> {
                                        Double sampleTime = checked.get(evaluated);
                                        if (sampleTime == null
                                                || success.getOrDefault(evaluated, 0D) != 1D)
                                            return;
                                        Instant at;
                                        try {
                                            at = Instant.ofEpochMilli((long) (sampleTime * 1000));
                                        } catch (RuntimeException invalid) {
                                            return;
                                        }
                                        if (value < 0
                                                || definition.unit().equals("percent")
                                                        && value > 100
                                                || at.isBefore(start)
                                                || at.isAfter(now.plusSeconds(2))
                                                || at.getEpochSecond() > evaluated + 2
                                                || at.getEpochSecond() < evaluated - 90) return;
                                        Point point = new Point(at, value);
                                        points.putIfAbsent(at, point);
                                        observations.put(evaluated, point);
                                    });
                Point last = points.isEmpty() ? null : points.lastEntry().getValue();
                // Preserve past successes in the trend, but never use them to fill a failed or
                // missing dimension in the latest check, even within the normal freshness window.
                Point current =
                        latestEvaluation == null ? null : observations.get(latestEvaluation);
                boolean fresh = current != null && current.at().isAfter(now.minusSeconds(90));
                List<Point> history = new ArrayList<>(points.values());
                if (history.size() > 181)
                    history = history.subList(history.size() - 181, history.size());
                result.add(
                        new Metric(
                                definition.key(),
                                definition.label(),
                                dimension,
                                definition.unit(),
                                fresh ? current.value() : null,
                                last == null ? null : last.at(),
                                fresh
                                        ? "OBSERVED"
                                        : last != null && !last.at().isAfter(now.minusSeconds(90))
                                                ? "STALE"
                                                : "MISSING",
                                history));
            }
        }
        return result;
    }

    private static Map<Long, Double> values(JsonNode row) {
        Map<Long, Double> values = new LinkedHashMap<>();
        if (row == null) return values;
        for (JsonNode pair : row.path("values")) {
            if (!pair.isArray() || pair.size() != 2) continue;
            try {
                double value = Double.parseDouble(pair.get(1).asText());
                if (Double.isFinite(value)) values.put(pair.get(0).asLong(), value);
            } catch (RuntimeException invalid) {
                /* Missing samples are gaps. */
            }
        }
        return values;
    }
}
