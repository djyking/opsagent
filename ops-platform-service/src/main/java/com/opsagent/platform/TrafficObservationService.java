package com.opsagent.platform;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Aggregated platform and question traffic; absent or stale samples never become zero.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class TrafficObservationService {
    private final PrometheusAdapter prometheus;
    private TrafficGovernanceDtos.Overview cached;

    TrafficObservationService(PrometheusAdapter prometheus) {
        this.prometheus = prometheus;
    }

    synchronized TrafficGovernanceDtos.Overview overview() {
        if (cached != null && cached.refreshedAt().isAfter(Instant.now().minusSeconds(10)))
            return cached;
        var queries = queries(prometheus.maximumSampleAge());
        Map<String, CompletableFuture<Reading>> pending = new LinkedHashMap<>();
        queries.forEach(
                (key, query) ->
                        pending.put(
                                key,
                                CompletableFuture.supplyAsync(
                                        () -> {
                                            try {
                                                var samples = prometheus.query(query);
                                                return new Reading(
                                                        samples.isEmpty()
                                                                ? null
                                                                : samples.get(0).value(),
                                                        false);
                                            } catch (Exception exception) {
                                                if (exception instanceof InterruptedException)
                                                    Thread.currentThread().interrupt();
                                                return new Reading(null, true);
                                            }
                                        })));
        Map<String, Reading> readings = new LinkedHashMap<>();
        pending.forEach((key, future) -> readings.put(key, future.join()));
        cached = project(readings, Instant.now(), prometheus.maximumSampleAge());
        return cached;
    }

    static Map<String, String> queries(long maximumAge) {
        Map<String, String> queries = new LinkedHashMap<>();
        add(
                queries,
                "gateway",
                "http_server_requests_seconds_count",
                "opsagent-gateway",
                maximumAge);
        add(queries, "question", "opsagent_rag_sentinel_passed_total", "opsagent-rag", maximumAge);
        String blocked = selector("opsagent_rag_sentinel_blocked_total", "opsagent-rag");
        queries.put("questionBlocked", aggregate("increase", blocked, "opsagent-rag", maximumAge));
        queries.put(
                "gatewayApiRate",
                aggregate(
                        "rate",
                        selector("spring_cloud_gateway_requests_seconds_count", "opsagent-gateway"),
                        "opsagent-gateway",
                        maximumAge));
        return queries;
    }

    private static void add(
            Map<String, String> queries, String key, String metric, String job, long age) {
        String counter = selector(metric, job);
        queries.put(key + "Rate", aggregate("rate", counter, job, age));
        queries.put(key + "Count", aggregate("increase", counter, job, age));
        queries.put(key + "SampledAt", "max(timestamp(" + counter + "))");
    }

    private static String selector(String metric, String job) {
        return metric + "{job=\"" + job + "\",environment=\"PROD\"}";
    }

    private static String aggregate(String function, String counter, String job, long age) {
        // Keep the counter's full labels until after freshness/up filtering; do not mix routes,
        // instances or a stale pre-restart series. No `or vector(0)` fallback is permitted.
        return "sum("
                + function
                + "("
                + counter
                + "[5m]) and (time() - timestamp("
                + counter
                + ") <= "
                + Math.max(1, age)
                + ") and on(job,instance) (up{job=\""
                + job
                + "\",environment=\"PROD\"} == 1))";
    }

    static TrafficGovernanceDtos.Overview project(
            Map<String, Reading> readings, Instant now, long age) {
        return new TrafficGovernanceDtos.Overview(
                "PROMETHEUS",
                300,
                now,
                List.of(
                        stream(
                                readings,
                                now,
                                age,
                                "gateway",
                                "平台 / API 网关",
                                "ops-gateway",
                                "网关HTTP总请求含健康检查与监控采集，与服务观测请求速率同口径；API转发单列，不含Nginx静态资源，也不等同于在线人数。"),
                        stream(
                                readings,
                                now,
                                age,
                                "question",
                                "AI 问答入口",
                                "ops-rag-service",
                                "助手与同步/流式问答入口校验通过量，含系统指南；不等同于模型调用次数。Sentinel拦截不含独立AI预算限制。")));
    }

    private static TrafficGovernanceDtos.Stream stream(
            Map<String, Reading> readings,
            Instant now,
            long age,
            String key,
            String label,
            String serviceId,
            String scope) {
        Reading rate = readings.getOrDefault(key + "Rate", new Reading(null, true));
        Reading count = readings.getOrDefault(key + "Count", new Reading(null, true));
        Reading sample = readings.getOrDefault(key + "SampledAt", new Reading(null, true));
        Reading blocked = readings.getOrDefault(key + "Blocked", new Reading(null, false));
        Reading api = readings.getOrDefault(key + "ApiRate", new Reading(null, false));
        Instant sampledAt =
                valid(sample.value()) ? Instant.ofEpochMilli((long) (sample.value() * 1000)) : null;
        boolean fresh =
                sampledAt != null
                        && !sampledAt.isBefore(now.minusSeconds(Math.max(1, age)))
                        && !sampledAt.isAfter(now.plusSeconds(5));
        Double rps = fresh && valid(rate.value()) ? rate.value() : null;
        Double requests = fresh && valid(count.value()) ? count.value() : null;
        Double blocks = fresh && valid(blocked.value()) ? blocked.value() : null;
        Double apiRate = fresh && valid(api.value()) ? api.value() : null;
        boolean failed =
                rate.failed()
                        || count.failed()
                        || sample.failed()
                        || blocked.failed()
                        || api.failed();
        String status =
                rps != null && requests != null && !failed
                        ? "AVAILABLE"
                        : rps != null || requests != null
                                ? "PARTIAL"
                                : failed ? "UNAVAILABLE" : "NO_SAMPLES";
        String message =
                switch (status) {
                    case "AVAILABLE" -> "最近5分钟平均速率；请求量由计数器增量估算。0表示有效采样窗口内未记录到请求。";
                    case "PARTIAL" -> "部分指标暂不可用，缺失项显示—；保留已取得的真实指标。";
                    case "UNAVAILABLE" -> "采集查询暂不可用；—不代表没有流量。";
                    default -> "暂无有效样本：可能采样不足、尚未采集或采集已中断；—不代表0。";
                };
        return new TrafficGovernanceDtos.Stream(
                key, label, serviceId, status, rps, requests, blocks, apiRate, sampledAt, scope,
                message);
    }

    private static boolean valid(Double value) {
        return value != null && Double.isFinite(value) && value >= 0;
    }

    record Reading(Double value, boolean failed) {}
}
