package com.opsagent.platform;

import com.opsagent.common.security.SecurityUsers;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 持续巡检复用自动化调度器，以同一节点契约保存执行与结论；不调用模型或另建调度器。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class ObservabilityInspectionService {
    private final TopologyAggregationService topology;
    private final ObservabilityRepository repository;
    private final ItsmPlatformService cmdb;
    private final InspectionExecutionRepository executions;
    private final String executorId = "platform:" + UUID.randomUUID();
    private final ThreadPoolExecutor captures =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "observability-inspection-capture");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    @Value("${ops.operations.inspection-enabled:false}")
    private boolean enabled;

    @Value("${ops.operations.inspection-interval-ms:900000}")
    private long intervalMs = 900000;

    @Value("${ops.operations.inspection-timeout-ms:120000}")
    private long timeoutMs = 120000;

    ObservabilityInspectionService(
            TopologyAggregationService topology,
            ObservabilityRepository repository,
            ItsmPlatformService cmdb,
            InspectionExecutionRepository executions) {
        this.topology = topology;
        this.repository = repository;
        this.cmdb = cmdb;
        this.executions = executions;
    }

    Map<String, Object> overview(String environment) {
        var data = topology.topology(environment, "15m", "CONFIGURED");
        var schedule = executions.schedule(enabled, intervalMs);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var node : nodes(data)) {
            String code = String.valueOf(node.get("ciCode"));
            var history = combinedHistory(code);
            Map<String, Object> latest = history.isEmpty() ? Map.of() : history.get(0);
            Map<String, Object> item = new LinkedHashMap<>(latest);
            item.put("id", code);
            item.put("ciCode", code);
            item.put("targetId", code);
            item.put("environment", node.get("environment"));
            item.put(
                    "checkId",
                    latest.getOrDefault(
                            "checkId", "HEALTH_CHECK:" + node.get("environment") + ":" + code));
            item.put("name", node.get("ciName") + "运行检查");
            item.put("currentHealth", node.get("health"));
            item.put("currentNode", node);
            item.put("observation", node.get("observation"));
            item.put("status", latest.getOrDefault("status", "NOT_RUN"));
            item.put("lastCheckedAt", latest.get("lastCheckedAt"));
            item.put("durationMs", latest.get("durationMs"));
            item.put("executionStatus", latest.getOrDefault("executionStatus", "NOT_RUN"));
            item.put("result", latest.getOrDefault("result", "UNKNOWN"));
            item.put(
                    "reasonCode",
                    latest.getOrDefault("reasonCode", enabled ? "NEVER_RUN" : "PLAN_DISABLED"));
            item.put(
                    "summary",
                    latest.getOrDefault(
                            "summary", enabled ? "尚无持久化巡检记录，可立即检查" : "自动巡检计划已关闭；可手动检查"));
            item.put(
                    "consecutiveFailures",
                    history.stream()
                            .filter(
                                    row ->
                                            !List.of("SKIPPED", "RUNNING")
                                                    .contains(row.get("executionStatus")))
                            .takeWhile(row -> "FAILED".equals(row.get("status")))
                            .count());
            item.put("activeAlertCount", node.get("activeAlertCount"));
            item.put("automationId", "HEALTH_CHECK");
            item.put("scheduleEnabled", enabled);
            item.put("nextRunAt", schedule.get("nextRunAt"));
            items.add(item);
        }
        Map<String, Object> counts = new LinkedHashMap<>(executions.today(environment));
        // Legacy rows remain visible without assigning old records an invented execution identity.
        repository
                .today(environment)
                .forEach(
                        (key, value) ->
                                counts.merge(
                                        key,
                                        value,
                                        (left, right) ->
                                                ((Number) left).longValue()
                                                        + ((Number) right).longValue()));
        return Map.of(
                "items",
                items,
                "checkedAt",
                data.get("checkedAt"),
                "today",
                counts,
                "schedule",
                schedule,
                "countUnit",
                "SERVICE_CHECK",
                "coverage",
                "执行状态与检查结论分别记录；只检查已接入真实证据，缺少证据不显示通过");
    }

    Map<String, Object> run(String code) {
        var target = cmdb.ci(code);
        var reservation =
                executions.reserve(
                        List.of(target),
                        SecurityUsers.current().userId(),
                        executorId,
                        "MANUAL",
                        true,
                        intervalMs,
                        timeout(),
                        Instant.now());
        if (reservation.acquired()) capture(reservation);
        return executions.run(reservation.runId()).get(0);
    }

    InspectionExecutionRepository.Reservation reserveWorkflow(boolean scheduled, long actor) {
        return executions.reserve(
                cmdb.cis(null, null),
                actor,
                executorId,
                scheduled ? "SCHEDULED" : "MANUAL_WORKFLOW",
                !scheduled || enabled,
                intervalMs,
                timeout(),
                Instant.now());
    }

    void bindWorkflow(InspectionExecutionRepository.Reservation reservation, long workflowId) {
        executions.bindWorkflow(reservation, workflowId);
    }

    boolean capture(InspectionExecutionRepository.Reservation reservation) {
        Future<Map<String, Object>> future = null;
        String state = "COMPLETED";
        String reason = "TARGET_MISSING";
        try {
            long remaining = reservation.deadline().toEpochMilli() - Instant.now().toEpochMilli();
            if (remaining <= 0) throw new TimeoutException();
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            future =
                    captures.submit(
                            () -> {
                                var context = SecurityContextHolder.createEmptyContext();
                                context.setAuthentication(authentication);
                                SecurityContextHolder.setContext(context);
                                try {
                                    return topology.topology("ALL", "15m", "CONFIGURED");
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            var data = future.get(remaining, TimeUnit.MILLISECONDS);
            Instant finishedAt = Instant.now();
            for (var node : nodes(data)) executions.complete(reservation, node, finishedAt);
        } catch (TimeoutException exception) {
            state = "TIMED_OUT";
            reason = "CAPTURE_TIMEOUT";
        } catch (RejectedExecutionException exception) {
            state = "SKIPPED";
            reason = "EXECUTOR_BUSY";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            state = "FAILED";
            reason = "CAPTURE_FAILED";
        } catch (ExecutionException | RuntimeException exception) {
            state = "FAILED";
            reason = "CAPTURE_FAILED";
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
            executions.finish(reservation, state, reason, Instant.now());
        }
        var results = executions.run(reservation.runId());
        return !results.isEmpty()
                && results.stream()
                        .allMatch(
                                row ->
                                        "COMPLETED".equals(row.get("executionStatus"))
                                                && "PASS".equals(row.get("result")));
    }

    void finish(
            InspectionExecutionRepository.Reservation reservation, String state, String reason) {
        executions.finish(reservation, state, reason, Instant.now());
    }

    List<Long> recoverExpired() {
        executions.recoverExpired(Instant.now());
        return executions.expiredWorkflows();
    }

    Map<String, Object> history(String code) {
        cmdb.ci(code);
        return Map.of("items", combinedHistory(code), "limit", 50);
    }

    private List<Map<String, Object>> combinedHistory(String code) {
        List<Map<String, Object>> rows = new ArrayList<>(executions.history(code));
        for (var legacy : repository.history(code)) {
            var row = new LinkedHashMap<>(legacy);
            row.put("executionStatus", "LEGACY_RECORDED");
            row.put("reasonCode", "LEGACY_EXECUTION_METADATA_UNAVAILABLE");
            row.put(
                    "result",
                    switch (String.valueOf(row.get("status"))) {
                        case "SUCCESS" -> "PASS";
                        case "FAILED", "WARNING" -> "ABNORMAL";
                        default -> "UNKNOWN";
                    });
            rows.add(row);
        }
        return rows.stream()
                .sorted(Comparator.comparing(this::orderingTime).reversed())
                .limit(50)
                .toList();
    }

    private Instant orderingTime(Map<String, Object> row) {
        Object value = row.getOrDefault("scheduledFor", row.get("lastCheckedAt"));
        if (value == null) return Instant.EPOCH;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (java.time.format.DateTimeParseException exception) {
            return Instant.EPOCH;
        }
    }

    private long timeout() {
        return Math.max(1000, Math.min(300000, timeoutMs));
    }

    @PreDestroy
    void close() {
        captures.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("nodes");
    }
}
