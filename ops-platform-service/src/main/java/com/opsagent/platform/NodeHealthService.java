package com.opsagent.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后端统一计算节点健康；采集状态与业务探针的结论和时间分别保留。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class NodeHealthService {
    @Value("${ops.observability.error-rate-threshold:5}")
    private double errorThreshold = 5;

    @Value("${ops.observability.p95-threshold-ms:1000}")
    private double latencyThreshold = 1000;

    @Value("${ops.observability.sample-max-age-seconds:90}")
    private long maximumAge = 90;

    /**
     * @author heyu
     */
    record Health(
            String health,
            String reason,
            Integer healthyInstances,
            Integer totalInstances,
            Instant observedAt,
            String reasonCode,
            String scope) {}

    Health compute(
            String job,
            String lifecycle,
            List<PrometheusAdapter.Sample> samples,
            List<AlertmanagerAdapter.Alert> alerts,
            Map<String, Object> metrics,
            Instant now,
            Double businessProbe,
            Instant businessAt) {
        return compute(
                job, lifecycle, samples, alerts, metrics, now, businessProbe, businessAt, null);
    }

    Health compute(
            String job,
            String lifecycle,
            List<PrometheusAdapter.Sample> samples,
            List<AlertmanagerAdapter.Alert> alerts,
            Map<String, Object> metrics,
            Instant now,
            Double businessProbe,
            Instant businessAt,
            Integer expectedInstances) {
        return compute(
                job,
                lifecycle,
                samples,
                alerts,
                metrics,
                now,
                businessProbe,
                businessAt,
                expectedInstances,
                false);
    }

    Health compute(
            String job,
            String lifecycle,
            List<PrometheusAdapter.Sample> samples,
            List<AlertmanagerAdapter.Alert> alerts,
            Map<String, Object> metrics,
            Instant now,
            Double businessProbe,
            Instant businessAt,
            Integer expectedInstances,
            boolean probeExpected) {
        List<PrometheusAdapter.Sample> up =
                samples.stream()
                        .filter(
                                sample ->
                                        job.equals(sample.job())
                                                && !job.isBlank()
                                                && sample.observedAt()
                                                        .isAfter(now.minusSeconds(maximumAge))
                                                && !sample.observedAt()
                                                        .isAfter(now.plusSeconds(15)))
                        .toList();
        int online = (int) up.stream().filter(sample -> sample.value() == 1).count();
        Integer count = up.isEmpty() ? null : up.size();
        if (expectedInstances != null && expectedInstances > 0)
            count = Math.max(expectedInstances, up.size());
        Integer healthy = up.isEmpty() ? null : online;
        Instant observed =
                up.stream()
                        .map(PrometheusAdapter.Sample::observedAt)
                        .min(Instant::compareTo)
                        .orElse(null);
        // Registration/maintenance are independent lifecycle overlays, never health evidence.
        boolean probeFresh =
                businessAt != null
                        && businessAt.isAfter(now.minusSeconds(30))
                        && !businessAt.isAfter(now.plusSeconds(15));
        var critical =
                alerts.stream()
                        .filter(
                                alert ->
                                        !collectionAlert(alert)
                                                && Set.of("P1", "CRITICAL")
                                                        .contains(alert.severity()))
                        .findFirst();
        if (critical.isPresent()) {
            return new Health(
                    "CRITICAL",
                    "存在未恢复的严重业务告警，请结合证据定位",
                    healthy,
                    count,
                    alertTime(critical.get()),
                    "ACTIVE_CRITICAL_ALERT",
                    "ACTIVE_ALERT");
        }
        if (probeFresh && businessProbe != null && businessProbe == 0) {
            return new Health(
                    "CRITICAL",
                    "最新真实业务探针失败",
                    healthy,
                    count,
                    businessAt,
                    "BUSINESS_PROBE_FAILED",
                    "BUSINESS_PROBE");
        }
        var warning =
                alerts.stream()
                        .filter(
                                alert ->
                                        !collectionAlert(alert)
                                                && Set.of("P2", "WARNING", "WARN")
                                                        .contains(alert.severity()))
                        .findFirst();
        if (warning.isPresent()) {
            return new Health(
                    "DEGRADED",
                    "存在未恢复的警告级别业务告警",
                    healthy,
                    count,
                    alertTime(warning.get()),
                    "ACTIVE_WARNING_ALERT",
                    "ACTIVE_ALERT");
        }
        if (above(metrics.get("errorRate"), errorThreshold)
                || above(metrics.get("p95Ms"), latencyThreshold)) {
            return new Health(
                    "DEGRADED",
                    "请求错误率或 P95 超过服务端配置阈值",
                    healthy,
                    count,
                    observed,
                    "REQUEST_THRESHOLD_EXCEEDED",
                    "REQUEST_WINDOW");
        }
        if (above(metrics.get("memoryAlarm"), 0)
                || above(metrics.get("diskAlarm"), 0)
                || above(metrics.get("recoveryMode"), 0)) {
            return new Health(
                    "DEGRADED",
                    "中间件原生指标报告资源告警或恢复模式",
                    healthy,
                    count,
                    observed,
                    "NATIVE_ALARM_ACTIVE",
                    "NATIVE_METRICS");
        }
        if (probeFresh && businessProbe != null && businessProbe == 1) {
            return new Health(
                    "HEALTHY",
                    "最新独立业务探针成功；采集状态另列",
                    healthy,
                    count,
                    businessAt,
                    "BUSINESS_PROBE_SUCCEEDED",
                    "BUSINESS_PROBE");
        }
        if (probeExpected && businessProbe == null) {
            return new Health(
                    "UNKNOWN",
                    "已配置的独立业务探针暂不可用，不能用JVM或HTTP指标代替",
                    healthy,
                    count,
                    businessAt,
                    "BUSINESS_PROBE_UNAVAILABLE",
                    "BUSINESS_PROBE");
        }
        if (businessProbe != null && !probeFresh) {
            return new Health(
                    "UNKNOWN",
                    "业务探针已经过期，不能用采集在线代替业务验证",
                    healthy,
                    count,
                    businessAt,
                    "BUSINESS_PROBE_STALE",
                    "BUSINESS_PROBE");
        }
        if (up.isEmpty() || count != null && online < count) {
            return new Health(
                    "UNKNOWN",
                    "采集缺失或失败；没有足够的独立证据判断业务状态",
                    healthy,
                    count,
                    observed,
                    "BUSINESS_EVIDENCE_MISSING",
                    "BUSINESS");
        }
        String scope =
                metrics.get("rps") instanceof Number
                        ? "REQUEST_WINDOW"
                        : metrics.get("cpuUsage") instanceof Number
                                        || metrics.get("memoryUsage") instanceof Number
                                ? "JVM_RUNTIME"
                                : metrics.values().stream().anyMatch(Number.class::isInstance)
                                        ? "NATIVE_METRICS"
                                        : "BUSINESS";
        boolean evidence = !"BUSINESS".equals(scope);
        return new Health(
                evidence ? "HEALTHY" : "UNKNOWN",
                evidence ? "已观测范围未越限；未覆盖业务仍未知" : "仅指标端点可抓取；尚无业务或运行指标证据",
                healthy,
                count,
                observed,
                evidence ? "OBSERVED_SCOPE_HEALTHY" : "BUSINESS_EVIDENCE_MISSING",
                scope);
    }

    private boolean collectionAlert(AlertmanagerAdapter.Alert alert) {
        return Set.of("OpsAgentServiceDown", "OpsAgentScrapeFailed", "TargetDown", "PrometheusDown")
                .contains(alert.title());
    }

    private Instant alertTime(AlertmanagerAdapter.Alert alert) {
        try {
            return Instant.parse(alert.startsAt());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean above(Object value, double threshold) {
        return value instanceof Number number && number.doubleValue() > threshold;
    }
}
