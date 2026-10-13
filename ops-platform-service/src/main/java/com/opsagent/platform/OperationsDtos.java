package com.opsagent.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

/**
 * 运维中心只返回经过筛选的运行证据与执行记录，不暴露内部地址、密钥或配置正文。
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class OperationsDtos {
    private OperationsDtos() {}

    /** @author heyu */
    public record Target(String service, String ciCode, String health, String observedAt) {}

    /** @author heyu */
    public record Point(Instant timestamp, double value) {}

    /** @author heyu */
    public record Metric(
            String id, String label, String job, String unit, Double currentValue, Double forecastValue,
            Double slopePerMinute, int sampleCount, String status, String method, String reason,
            Instant observedAt, List<Point> points) {
        Metric withoutPoints() {
            return new Metric(id, label, job, unit, currentValue, forecastValue, slopePerMinute,
                    sampleCount, status, method, reason, observedAt, List.of());
        }
    }

    /** @author heyu */
    public record Risk(
            String key, String severity, String title, String detail, String evidence, String recommendation) {}

    /** @author heyu */
    public record NacosService(String name, Integer instanceCount, Integer healthyInstanceCount) {}

    /** @author heyu */
    public record Configuration(String dataId, String group, String modifiedAt) {}

    /** @author heyu */
    public record Nacos(
            String status, String message, Integer serviceCount, Integer healthyInstanceCount,
            Integer configurationCount, List<NacosService> services, List<Configuration> configurations) {}

    /** @author heyu */
    public record FlowRule(String resource, String grade, double count, String controlBehavior) {}

    /** @author heyu */
    public record Sentinel(
            String status, String message, String ruleSource, List<FlowRule> rules,
            Double passedTotal, Double blockedTotal, String metricsObservedAt) {}

    /** @author heyu */
    public record Overview(
            Instant capturedAt, String source, String status, String summary, int windowMinutes,
            List<Target> targets, List<Metric> metrics, List<Risk> risks, Nacos nacos, Sentinel sentinel) {
        Overview context() {
            return new Overview(capturedAt, source, status, summary, windowMinutes, targets,
                    metrics.stream().map(Metric::withoutPoints).toList(), risks,
                    new Nacos(nacos.status(), nacos.message(), nacos.serviceCount(), nacos.healthyInstanceCount(),
                            nacos.configurationCount(), List.of(), List.of()), sentinel);
        }
    }

    /** @author heyu */
    public record Workflow(
            String code, String title, String description, String mode, List<String> steps,
            boolean available, String unavailableReason, boolean scheduleEnabled,
            int intervalMinutes, Instant lastRunAt) {}

    /** @author heyu */
    public record StartRun(
            @NotBlank @Pattern(regexp = "HEALTH_CHECK|ISOLATED_DRILL") String workflowCode,
            @Pattern(regexp = "SERVICE_UNAVAILABLE") String scenario) {}

    /** @author heyu */
    public record Step(
            long id, int sequence, String title, String status, String detail, String evidence,
            Instant startedAt, Instant finishedAt) {}

    /** @author heyu */
    public record Audit(String action, String detail, Instant createdAt, String actor) {}

    /** @author heyu */
    public record Run(
            long id, String workflowCode, String title, String mode, String status, String summary,
            Instant startedAt, Instant finishedAt, String actor) {}

    /** @author heyu */
    public record RunDetail(
            long id, String workflowCode, String title, String mode, String status, String summary,
            Instant startedAt, Instant finishedAt, String actor, List<Step> steps, List<Audit> audits) {
        static RunDetail from(Run run, List<Step> steps, List<Audit> audits) {
            return new RunDetail(run.id(), run.workflowCode(), run.title(), run.mode(), run.status(), run.summary(),
                    run.startedAt(), run.finishedAt(), run.actor(), steps, audits);
        }
    }
}
