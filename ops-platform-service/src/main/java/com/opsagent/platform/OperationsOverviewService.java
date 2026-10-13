package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将真实监控时序、CMDB 归属与治理元数据组合成可解释的运维证据快照。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class OperationsOverviewService {
    private static final Map<String, String> SERVICE_CIS =
            Map.of(
                    "opsagent-gateway",
                    "ops-gateway",
                    "opsagent-auth",
                    "ops-auth-service",
                    "opsagent-ticket",
                    "ops-ticket-service",
                    "opsagent-knowledge",
                    "ops-knowledge-service",
                    "opsagent-rag",
                    "ops-rag-service",
                    "opsagent-platform",
                    "ops-platform-service",
                    "opsagent-agent",
                    "ops-agent-service",
                    "opsagent-demo-order",
                    "ops-demo-order-service");
    private static final ObjectMapper BINDING_JSON = new ObjectMapper();
    private static final String HEAP_QUERY =
            "100 * sum by(job)(jvm_memory_used_bytes{area=\"heap\"})"
                    + " / sum by(job)(jvm_memory_max_bytes{area=\"heap\"})";
    private static final String REQUEST_RATE =
            "sum by(job)(rate(http_server_requests_seconds_count[5m]))";
    private static final String ERROR_QUERY =
            "(100 * (sum by(job)(rate(http_server_requests_seconds_count"
                    + "{status=~\"5..\"}[5m])) or on(job) (0 * "
                    + REQUEST_RATE
                    + ")) / ("
                    + REQUEST_RATE
                    + " > 0)) and on(job) (min by(job)(up) == 1)";
    private final MonitoringService monitoring;
    private final ItsmPlatformRepository cmdb;
    private final OperationsIntegrationClient integrations;
    private final OperationsTrendAnalyzer analyzer;
    private final HostResourceService hosts;
    private OperationsDtos.Overview cached;

    OperationsOverviewService(
            MonitoringService monitoring,
            ItsmPlatformRepository cmdb,
            OperationsIntegrationClient integrations,
            OperationsTrendAnalyzer analyzer) {
        this(monitoring, cmdb, integrations, analyzer, null);
    }

    @Autowired
    OperationsOverviewService(
            MonitoringService monitoring,
            ItsmPlatformRepository cmdb,
            OperationsIntegrationClient integrations,
            OperationsTrendAnalyzer analyzer,
            HostResourceService hosts) {
        this.monitoring = monitoring;
        this.cmdb = cmdb;
        this.integrations = integrations;
        this.analyzer = analyzer;
        this.hosts = hosts;
    }

    synchronized OperationsDtos.Overview overview(int requestedWindow, boolean fresh) {
        return collect(requestedWindow, fresh, true);
    }

    OperationsDtos.Overview inspection() {
        return collect(60, true, false);
    }

    private OperationsDtos.Overview collect(
            int requestedWindow, boolean fresh, boolean governance) {
        int window = Math.max(15, Math.min(360, requestedWindow));
        Instant now = Instant.now();
        if (governance
                && !fresh
                && cached != null
                && cached.windowMinutes() == window
                && cached.capturedAt().isAfter(now.minusSeconds(20))) return cached;
        ServiceSelection selection = serviceSelection();
        List<OperationsDtos.Target> targets = targets(selection);
        List<OperationsDtos.Metric> metrics = new ArrayList<>();
        metrics.addAll(query("heap", "JVM堆使用率", HEAP_QUERY, 85, window, now, selection.jobs()));
        metrics.addAll(
                query("http5xx", "HTTP 5xx比例", ERROR_QUERY, 5, window, now, selection.jobs()));
        List<OperationsDtos.Risk> risks = new ArrayList<>();
        for (var target : targets) {
            if ("down".equals(target.health()))
                risks.add(
                        new OperationsDtos.Risk(
                                "target-" + target.service(),
                                "CRITICAL",
                                target.service() + " 采集异常",
                                "Prometheus 最近一次无法抓取该服务。",
                                "来源：Prometheus targets；最近抓取：" + target.observedAt(),
                                "检查服务健康、抓取端点与网络；采集失败本身不能证明业务已停止。"));
        }
        for (var metric : metrics) {
            if ("RISK".equals(metric.status()))
                risks.add(
                        new OperationsDtos.Risk(
                                metric.id() + "-" + metric.job(),
                                "WARNING",
                                metric.job() + " " + metric.label() + "需要关注",
                                metric.reason(),
                                "Prometheus；"
                                        + metric.sampleCount()
                                        + "个样本；最近值 "
                                        + metric.currentValue()
                                        + metric.unit(),
                                "先确认负载、GC与请求变化，再结合工单和服务依赖分析原因；趋势不等于故障诊断。"));
        }
        if (metrics.stream().anyMatch(metric -> "UNKNOWN".equals(metric.status()))) {
            risks.add(
                    new OperationsDtos.Risk(
                            "coverage",
                            "INFO",
                            "部分指标证据不足",
                            "没有样本或样本过期的指标显示未知，不能按0或健康处理。",
                            "来源：query_range 查询结果与样本时间",
                            "确认指标已暴露并被采集；HTTP错误率需要有效请求基数，无流量时显示未知。"));
        }
        boolean hostUnknown = addHosts(metrics, risks, window, now);
        boolean attention = risks.stream().anyMatch(risk -> !"INFO".equals(risk.severity()));
        boolean unknown =
                targets.isEmpty()
                        || targets.stream().anyMatch(target -> "unknown".equals(target.health()))
                        || metrics.stream().anyMatch(metric -> "UNKNOWN".equals(metric.status()))
                        || hostUnknown;
        String status = attention ? "ATTENTION" : unknown ? "UNKNOWN" : "HEALTHY";
        String summary =
                attention
                        ? "发现需要确认的运行风险，请根据来源和时间核对后处理。"
                        : unknown
                                ? "已采集的证据未发现越限；部分观测尚不完整，不能确认全系统健康。"
                                : "已观测的服务与指标未发现越限；趋势仅描述当前采样窗口。";
        OperationsDtos.Overview result =
                new OperationsDtos.Overview(
                        now,
                        governance ? "Prometheus / Nacos / Sentinel / CMDB" : "Prometheus / CMDB",
                        status,
                        summary,
                        window,
                        targets,
                        metrics,
                        risks,
                        governance
                                ? integrations.nacos()
                                : new OperationsDtos.Nacos(
                                        "NOT_COLLECTED",
                                        "只读巡检只收集运行指标，不查询治理接口。",
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        List.of()),
                        governance
                                ? integrations.sentinel()
                                : new OperationsDtos.Sentinel(
                                        "NOT_COLLECTED",
                                        "只读巡检不查询需要用户认证的Sentinel运行时接口。",
                                        "",
                                        List.of(),
                                        null,
                                        null,
                                        null));
        if (governance) cached = result;
        return result;
    }

    private boolean addHosts(
            List<OperationsDtos.Metric> metrics,
            List<OperationsDtos.Risk> risks,
            int window,
            Instant now) {
        HostResourceService.Snapshot snapshot;
        try {
            snapshot = hosts == null ? null : hosts.read(window, null, true);
        } catch (RuntimeException unavailable) {
            snapshot = null;
        }
        if (snapshot == null || snapshot.hosts().isEmpty()) {
            risks.add(
                    new OperationsDtos.Risk(
                            "host-coverage",
                            "INFO",
                            "主机资源证据尚不可读",
                            "没有登记主机或当前采集不可读；不能用 JVM 指标推断物理主机健康。",
                            "主机采集 / CMDB",
                            "核对主机登记、采集任务与最新样本。"));
            return true;
        }
        boolean unknown = false;
        for (var host : snapshot.hosts()) {
            unknown |= !"READY".equals(host.status());
            for (var metric : host.metrics()) {
                if (!Set.of(
                                "cpuUsage",
                                "physicalMemoryUsage",
                                "diskUsage",
                                "networkReceiveRate",
                                "networkTransmitRate")
                        .contains(metric.key())) continue;
                String title =
                        host.ciName()
                                + (metric.dimension().isBlank() ? "" : " " + metric.dimension());
                boolean pressure =
                        "percent".equals(metric.unit())
                                && metric.value() != null
                                && metric.value() >= 90;
                metrics.add(
                        new OperationsDtos.Metric(
                                "host-" + metric.key() + "-" + metric.dimension(),
                                title + " " + metric.label(),
                                host.ciCode(),
                                metric.unit().equals("percent") ? "%" : metric.unit(),
                                metric.value(),
                                null,
                                null,
                                metric.points().size(),
                                !"OBSERVED".equals(metric.status())
                                        ? "UNKNOWN"
                                        : pressure ? "RISK" : "OK",
                                "真实主机采样；不外推，不插值，不将收发速率换成缺少分母的百分比",
                                host.job() + "；来源时间 " + metric.sampledAt(),
                                metric.sampledAt(),
                                metric.points().stream()
                                        .map(
                                                point ->
                                                        new OperationsDtos.Point(
                                                                point.at(), point.value()))
                                        .toList()));
                if (pressure)
                    risks.add(
                            new OperationsDtos.Risk(
                                    "host-" + metric.key() + "-" + metric.dimension(),
                                    "WARNING",
                                    title + " " + metric.label() + "需要关注",
                                    "当前使用率达到主机巡检关注值90%；不等于业务故障。",
                                    metric.value() + "%；原始样本 " + metric.sampledAt(),
                                    "结合实际负载核对容量与持续时间；不自动重启或修改配置。"));
            }
            risks.add(
                    new OperationsDtos.Risk(
                            "host-coverage-" + host.ciCode(),
                            "INFO",
                            "主机资源观测范围",
                            host.ciName() + "：" + host.status() + "；CPU、物理内存、已登记磁盘及网卡分别采样。",
                            host.job() + "；最新样本 " + host.observedAt(),
                            "此范围是宿主操作系统的整体统计，不是单个容器资源。"));
        }
        return unknown;
    }

    private record ServiceSelection(Map<String, String> jobs, Set<String> catalog) {}

    private ServiceSelection serviceSelection() {
        Map<String, String> jobs = new LinkedHashMap<>(SERVICE_CIS);
        Set<String> catalog = new HashSet<>();
        try {
            var inventory = cmdb.cis(null, null);
            Set<String> hostJobs = new HashSet<>();
            hostJobs.add(HostResourceService.JOB);
            inventory.stream()
                    .filter(row -> "HOST".equals(row.get("ciType")))
                    .forEach(row -> hostJobs.add(boundJob(row)));
            for (var row : inventory) {
                String ci = String.valueOf(row.get("ciCode"));
                if (!SERVICE_CIS.containsValue(ci) || "HOST".equals(row.get("ciType"))) continue;
                catalog.add(ci);
                String job = boundJob(row);
                if (job.isBlank()
                        || hostJobs.contains(job)
                        || jobs.containsKey(job) && !ci.equals(jobs.get(job))) continue;
                jobs.entrySet().removeIf(entry -> ci.equals(entry.getValue()));
                jobs.put(job, ci);
            }
        } catch (RuntimeException ignored) {
            // Keep the original eight service defaults when the inventory is unavailable.
        }
        return new ServiceSelection(jobs, catalog);
    }

    private String boundJob(Map<String, Object> row) {
        Object binding = row.get("bindings");
        if (binding instanceof Map<?, ?> values) {
            Object job = values.get("prometheusJob");
            return job == null ? "" : String.valueOf(job).trim();
        }
        if (binding instanceof JsonNode node) return node.path("prometheusJob").asText("").trim();
        try {
            Object metadata = row.get("metadataJson");
            return metadata == null
                    ? ""
                    : BINDING_JSON
                            .readTree(String.valueOf(metadata))
                            .path("bindings")
                            .path("prometheusJob")
                            .asText("")
                            .trim();
        } catch (Exception malformed) {
            return "";
        }
    }

    List<OperationsDtos.Target> targets() {
        return targets(serviceSelection());
    }

    private List<OperationsDtos.Target> targets(ServiceSelection selection) {
        Map<String, Object> summary = monitoring.summary();
        Map<String, String> jobs = selection.jobs();
        Set<String> catalog = selection.catalog();
        Map<String, OperationsDtos.Target> byJob = new LinkedHashMap<>();
        Object rows = summary.get("services");
        if (rows instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> row)) continue;
                String job = String.valueOf(row.get("job"));
                if (!jobs.containsKey(job)) continue;
                String ci = jobs.get(job);
                String rawHealth = String.valueOf(row.get("health"));
                String health = Set.of("up", "down").contains(rawHealth) ? rawHealth : "unknown";
                String observedAt = String.valueOf(row.get("lastScrape"));
                try {
                    if (Instant.parse(observedAt).isBefore(Instant.now().minusSeconds(180)))
                        health = "unknown";
                } catch (RuntimeException ignored) {
                    observedAt = "";
                    health = "unknown";
                }
                var target =
                        new OperationsDtos.Target(
                                job, catalog.contains(ci) ? ci : "", health, observedAt);
                byJob.merge(
                        job,
                        target,
                        (previous, current) ->
                                targetSeverity(current.health()) > targetSeverity(previous.health())
                                        ? current
                                        : previous);
            }
        }
        jobs.forEach(
                (job, ci) -> {
                    byJob.putIfAbsent(
                            job,
                            new OperationsDtos.Target(
                                    job, catalog.contains(ci) ? ci : "", "unknown", ""));
                });
        List<OperationsDtos.Target> result = new ArrayList<>(byJob.values());
        result.sort(java.util.Comparator.comparing(OperationsDtos.Target::service));
        return result;
    }

    private int targetSeverity(String health) {
        return "down".equals(health) ? 2 : "unknown".equals(health) ? 1 : 0;
    }

    private List<OperationsDtos.Metric> query(
            String id,
            String label,
            String promql,
            double threshold,
            int window,
            Instant now,
            Map<String, String> jobs) {
        List<OperationsDtos.Metric> result = new ArrayList<>();
        try {
            JsonNode response =
                    monitoring.queryRange(
                            promql,
                            now.minusSeconds(window * 60L),
                            now,
                            Math.max(60, window * 60 / 180));
            if (!"success".equals(response.path("status").asText())) {
                throw new IllegalStateException("Prometheus query unavailable");
            }
            for (JsonNode series : response.path("data").path("result")) {
                String job = series.path("metric").path("job").asText();
                if (!jobs.containsKey(job)) continue;
                List<OperationsDtos.Point> points = new ArrayList<>();
                for (JsonNode pair : series.path("values")) {
                    if (points.size() >= 181 || !pair.isArray() || pair.size() != 2) continue;
                    try {
                        points.add(
                                new OperationsDtos.Point(
                                        Instant.ofEpochSecond(pair.get(0).asLong()),
                                        Double.parseDouble(pair.get(1).asText())));
                    } catch (RuntimeException ignored) {
                        // NaN and malformed samples are not facts.
                    }
                }
                result.add(analyzer.analyze(id, label, job, points, threshold, now));
            }
        } catch (Exception ignored) {
            // Missing jobs are filled with explicit unknown states below.
        }
        Set<String> seen = new HashSet<>();
        result.forEach(metric -> seen.add(metric.job()));
        jobs.keySet()
                .forEach(
                        job -> {
                            if (!seen.contains(job))
                                result.add(
                                        analyzer.unknown(
                                                id, label, job, "该服务没有可用的指标序列，或时序查询暂不可用；不能按0处理。"));
                        });
        result.sort(java.util.Comparator.comparing(OperationsDtos.Metric::job));
        return result;
    }
}
