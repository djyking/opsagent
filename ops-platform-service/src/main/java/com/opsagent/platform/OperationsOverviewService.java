package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
    private static final Map<String, String> SERVICE_CIS = Map.of(
            "opsagent-gateway", "ops-gateway", "opsagent-auth", "ops-auth-service",
            "opsagent-ticket", "ops-ticket-service", "opsagent-knowledge", "ops-knowledge-service",
            "opsagent-rag", "ops-rag-service", "opsagent-platform", "ops-platform-service",
            "opsagent-agent", "ops-agent-service", "opsagent-demo-order", "ops-demo-order-service");
    private static final String HEAP_QUERY = "100 * sum by(job)(jvm_memory_used_bytes{area=\"heap\"})"
            + " / sum by(job)(jvm_memory_max_bytes{area=\"heap\"})";
    private static final String REQUEST_RATE = "sum by(job)(rate(http_server_requests_seconds_count[5m]))";
    private static final String ERROR_QUERY = "(100 * (sum by(job)(rate(http_server_requests_seconds_count"
            + "{status=~\"5..\"}[5m])) or on(job) (0 * " + REQUEST_RATE + ")) / (" + REQUEST_RATE
            + " > 0)) and on(job) (min by(job)(up) == 1)";
    private final MonitoringService monitoring;
    private final ItsmPlatformRepository cmdb;
    private final OperationsIntegrationClient integrations;
    private final OperationsTrendAnalyzer analyzer;
    private OperationsDtos.Overview cached;

    OperationsOverviewService(MonitoringService monitoring, ItsmPlatformRepository cmdb,
            OperationsIntegrationClient integrations, OperationsTrendAnalyzer analyzer) {
        this.monitoring = monitoring;
        this.cmdb = cmdb;
        this.integrations = integrations;
        this.analyzer = analyzer;
    }

    synchronized OperationsDtos.Overview overview(int requestedWindow, boolean fresh) {
        return collect(requestedWindow, fresh, true);
    }

    OperationsDtos.Overview inspection() {
        return collect(60, true, false);
    }

    private OperationsDtos.Overview collect(int requestedWindow, boolean fresh, boolean governance) {
        int window = Math.max(15, Math.min(360, requestedWindow));
        Instant now = Instant.now();
        if (governance && !fresh && cached != null && cached.windowMinutes() == window
                && cached.capturedAt().isAfter(now.minusSeconds(20))) return cached;
        List<OperationsDtos.Target> targets = targets();
        List<OperationsDtos.Metric> metrics = new ArrayList<>();
        metrics.addAll(query("heap", "JVM堆使用率", HEAP_QUERY, 85, window, now));
        metrics.addAll(query("http5xx", "HTTP 5xx比例", ERROR_QUERY, 5, window, now));
        List<OperationsDtos.Risk> risks = new ArrayList<>();
        for (var target : targets) {
            if ("down".equals(target.health())) risks.add(new OperationsDtos.Risk("target-" + target.service(),
                    "CRITICAL", target.service() + " 采集异常", "Prometheus 最近一次无法抓取该服务。",
                    "来源：Prometheus targets；最近抓取：" + target.observedAt(),
                    "检查服务健康、抓取端点与网络；采集失败本身不能证明业务已停止。"));
        }
        for (var metric : metrics) {
            if ("RISK".equals(metric.status())) risks.add(new OperationsDtos.Risk(metric.id() + "-" + metric.job(),
                    "WARNING", metric.job() + " " + metric.label() + "需要关注", metric.reason(),
                    "Prometheus；" + metric.sampleCount() + "个样本；最近值 " + metric.currentValue() + "%",
                    "先确认负载、GC与请求变化，再结合工单和服务依赖分析原因；趋势不等于故障诊断。"));
        }
        if (metrics.stream().anyMatch(metric -> "UNKNOWN".equals(metric.status()))) {
            risks.add(new OperationsDtos.Risk("coverage", "INFO", "部分指标证据不足",
                    "没有样本或样本过期的指标显示未知，不能按0或健康处理。", "来源：query_range 查询结果与样本时间",
                    "确认指标已暴露并被采集；HTTP错误率需要有效请求基数，无流量时显示未知。"));
        }
        risks.add(new OperationsDtos.Risk("host-coverage", "INFO", "主机资源观测范围",
                "当前只采集Java服务指标；尚未接入宿主机磁盘、宿主机内存或Docker容器统计。",
                "没有 node_exporter / cAdvisor 指标源。", "当前不能预测主机磁盘耗尽；需要接入相应只读采集器。"));
        boolean attention = risks.stream().anyMatch(risk -> !"INFO".equals(risk.severity()));
        boolean unknown = targets.isEmpty() || targets.stream().anyMatch(target -> "unknown".equals(target.health()))
                || metrics.stream().anyMatch(metric -> "UNKNOWN".equals(metric.status()));
        String status = attention ? "ATTENTION" : unknown ? "UNKNOWN" : "HEALTHY";
        String summary = attention ? "发现需要确认的运行风险，请根据来源和时间核对后处理。"
                : unknown ? "已采集的证据未发现越限；部分观测尚不完整，不能确认全系统健康。"
                : "已观测的服务与指标未发现越限；趋势仅描述当前采样窗口。";
        OperationsDtos.Overview result = new OperationsDtos.Overview(now,
                governance ? "Prometheus / Nacos / Sentinel / CMDB" : "Prometheus / CMDB", status, summary,
                window, targets, metrics, risks,
                governance ? integrations.nacos() : new OperationsDtos.Nacos("NOT_COLLECTED",
                        "只读巡检只收集运行指标，不查询治理接口。", null, null, null, List.of(), List.of()),
                governance ? integrations.sentinel() : new OperationsDtos.Sentinel("NOT_COLLECTED",
                        "只读巡检不查询需要用户认证的Sentinel运行时接口。", "", List.of(), null, null, null));
        if (governance) cached = result;
        return result;
    }

    List<OperationsDtos.Target> targets() {
        Map<String, Object> summary = monitoring.summary();
        Set<String> catalog = new HashSet<>();
        try {
            cmdb.cis(null, null).forEach(row -> catalog.add(String.valueOf(row.get("ciCode"))));
        } catch (RuntimeException ignored) {
            // A catalog lookup failure does not turn scrape status into a guessed business status.
        }
        List<OperationsDtos.Target> result = new ArrayList<>();
        Object rows = summary.get("services");
        if (rows instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> row)) continue;
                String job = String.valueOf(row.get("job"));
                if (!SERVICE_CIS.containsKey(job)) continue;
                String ci = "opsagent-demo-order".equals(job) ? DemoTargetDtos.TARGET : SERVICE_CIS.get(job);
                String rawHealth = String.valueOf(row.get("health"));
                String health = Set.of("up", "down").contains(rawHealth) ? rawHealth : "unknown";
                String observedAt = String.valueOf(row.get("lastScrape"));
                try {
                    if (Instant.parse(observedAt).isBefore(Instant.now().minusSeconds(180))) health = "unknown";
                } catch (RuntimeException ignored) {
                    observedAt = "";
                    health = "unknown";
                }
                result.add(new OperationsDtos.Target(job, catalog.contains(ci) ? ci : "", health, observedAt));
            }
        }
        Set<String> seen = new HashSet<>();
        result.forEach(target -> seen.add(target.service()));
        SERVICE_CIS.forEach((job, ci) -> {
            if (!seen.contains(job)) result.add(new OperationsDtos.Target(job, catalog.contains(ci) ? ci : "",
                    "unknown", ""));
        });
        result.sort(java.util.Comparator.comparing(OperationsDtos.Target::service));
        return result;
    }

    private List<OperationsDtos.Metric> query(
            String id, String label, String promql, double threshold, int window, Instant now) {
        List<OperationsDtos.Metric> result = new ArrayList<>();
        try {
            JsonNode response = monitoring.queryRange(promql, now.minusSeconds(window * 60L), now,
                    Math.max(60, window * 60 / 180));
            if (!"success".equals(response.path("status").asText())) {
                throw new IllegalStateException("Prometheus query unavailable");
            }
            for (JsonNode series : response.path("data").path("result")) {
                String job = series.path("metric").path("job").asText();
                if (!SERVICE_CIS.containsKey(job)) continue;
                List<OperationsDtos.Point> points = new ArrayList<>();
                for (JsonNode pair : series.path("values")) {
                    if (points.size() >= 181 || !pair.isArray() || pair.size() != 2) continue;
                    try {
                        points.add(new OperationsDtos.Point(Instant.ofEpochSecond(pair.get(0).asLong()),
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
        SERVICE_CIS.keySet().forEach(job -> {
            if (!seen.contains(job)) result.add(analyzer.unknown(id, label, job,
                    "该服务没有可用的指标序列，或时序查询暂不可用；不能按0处理。"));
        });
        result.sort(java.util.Comparator.comparing(OperationsDtos.Metric::job));
        return result;
    }
}
