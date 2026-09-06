package com.opsagent.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 在现有巡检调度器下持久化时隙、跨进程租约和逐目标执行证据，不执行控制动作。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
class InspectionExecutionRepository {
    private static final String PLAN = "HEALTH_CHECK";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    InspectionExecutionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getDataSource())));
    }

    record Reservation(
            String runId,
            Instant scheduledFor,
            Instant nextRunAt,
            Instant deadline,
            boolean acquired) {}

    private record Plan(Instant nextRunAt, Instant leaseUntil) {}

    Reservation reserve(
            List<Map<String, Object>> targets,
            long actor,
            String executor,
            String source,
            boolean enabled,
            long intervalMs,
            long timeoutMs,
            Instant now) {
        return transactions.execute(
                status -> {
                    recoverExpired(now);
                    long interval = Math.max(60000, intervalMs);
                    Instant slot =
                            Instant.ofEpochMilli(
                                    Math.floorDiv(now.toEpochMilli(), interval) * interval);
                    jdbc.update(
                            "INSERT IGNORE INTO"
                                + " observability_inspection_plan(plan_key,next_run_at,updated_at)"
                                + " VALUES(?,?,?)",
                            PLAN,
                            ts(slot),
                            ts(now));
                    var plan =
                            Objects.requireNonNull(
                                    jdbc.queryForObject(
                                            "SELECT * FROM observability_inspection_plan WHERE"
                                                + " plan_key=? FOR UPDATE",
                                            this::plan,
                                            PLAN));
                    Instant due = plan.nextRunAt();
                    boolean scheduled = "SCHEDULED".equals(source);
                    if (scheduled && due.isAfter(now)) return null;
                    Instant next = due;
                    if (scheduled) {
                        if (due.plusMillis(interval).isBefore(now)
                                || due.plusMillis(interval).equals(now)) {
                            long missed =
                                    Math.floorDiv(
                                            now.toEpochMilli() - due.toEpochMilli(), interval);
                            Reservation gap =
                                    new Reservation(
                                            UUID.randomUUID().toString(), due, slot, now, false);
                            insert(
                                    targets,
                                    actor,
                                    executor,
                                    source,
                                    gap,
                                    "SKIPPED",
                                    enabled ? "MISSED_SCHEDULE" : "PLAN_DISABLED",
                                    Map.of(
                                            "missedSlots",
                                            missed,
                                            "missedThrough",
                                            slot.minusMillis(interval).toString()));
                            due = slot;
                        }
                        next = due.plusMillis(interval);
                        jdbc.update(
                                "UPDATE observability_inspection_plan SET"
                                        + " next_run_at=?,updated_at=? WHERE plan_key=?",
                                ts(next),
                                ts(now),
                                PLAN);
                    }
                    Instant lease = plan.leaseUntil();
                    boolean busy = lease != null && lease.isAfter(now);
                    String reason =
                            !enabled ? "PLAN_DISABLED" : busy ? "EXECUTOR_BUSY" : "EXECUTING";
                    Reservation reservation =
                            new Reservation(
                                    UUID.randomUUID().toString(),
                                    scheduled ? due : now,
                                    enabled ? next : null,
                                    now.plusMillis(timeoutMs),
                                    enabled && !busy);
                    insert(
                            targets,
                            actor,
                            executor,
                            source,
                            reservation,
                            reservation.acquired() ? "RUNNING" : "SKIPPED",
                            reason,
                            Map.of());
                    if (reservation.acquired()) {
                        jdbc.update(
                                "UPDATE observability_inspection_plan SET"
                                    + " lease_owner=?,lease_until=?,updated_at=? WHERE plan_key=?",
                                reservation.runId(),
                                ts(reservation.deadline()),
                                ts(now),
                                PLAN);
                    }
                    return reservation;
                });
    }

    private void insert(
            List<Map<String, Object>> targets,
            long actor,
            String executor,
            String source,
            Reservation reservation,
            String status,
            String reason,
            Map<String, Object> evidence) {
        Instant now = Instant.now();
        for (var target : targets) {
            String code = String.valueOf(target.get("ciCode"));
            String environment = String.valueOf(target.getOrDefault("environment", "UNKNOWN"));
            jdbc.update(
                    "INSERT INTO"
                        + " observability_inspection_execution(check_id,target_id,environment,scheduled_for,"
                        + "run_id,executor,started_at,finished_at,execution_status,result,reason_code,summary,"
                        + "evidence_json,next_run_at,lease_until,actor_id,source)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    PLAN + ":" + environment + ":" + code,
                    code,
                    environment,
                    ts(reservation.scheduledFor()),
                    reservation.runId(),
                    executor,
                    "RUNNING".equals(status) ? ts(now) : null,
                    "RUNNING".equals(status) ? null : ts(now),
                    status,
                    "UNKNOWN",
                    reason,
                    explanation(reason),
                    write(evidence),
                    ts(reservation.nextRunAt()),
                    ts(reservation.deadline()),
                    actor,
                    source);
        }
    }

    void bindWorkflow(Reservation reservation, long workflowId) {
        jdbc.update(
                "UPDATE observability_inspection_execution SET workflow_run_id=? WHERE run_id=?"
                        + " AND execution_status='RUNNING'",
                workflowId,
                reservation.runId());
    }

    void complete(Reservation reservation, Map<String, Object> node, Instant finishedAt) {
        String lifecycle = String.valueOf(node.getOrDefault("lifecycle", node.get("status")));
        String health = String.valueOf(node.get("health"));
        String result =
                List.of("INACTIVE", "RETIRED", "DISABLED").contains(lifecycle)
                        ? "NOT_APPLICABLE"
                        : switch (health) {
                            case "HEALTHY" -> "PASS";
                            case "DEGRADED", "CRITICAL" -> "ABNORMAL";
                            default -> "UNKNOWN";
                        };
        String reason =
                "NOT_APPLICABLE".equals(result)
                        ? "TARGET_INACTIVE"
                        : "PASS".equals(result)
                                ? "OBSERVED_SCOPE_PASS"
                                : "ABNORMAL".equals(result)
                                        ? ("DEGRADED".equals(health)
                                                ? "OBSERVED_DEGRADATION"
                                                : "OBSERVED_ANOMALY")
                                        : "OBSERVATION_INSUFFICIENT";
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (String key :
                List.of(
                        "health",
                        "metrics",
                        "observedAt",
                        "sampledAt",
                        "activeAlertCount",
                        "observation",
                        "observationState",
                        "healthScope",
                        "metricDetails",
                        "businessObservedAt")) {
            if (node.containsKey(key)) evidence.put(key, node.get(key));
        }
        evidence.put("coverage", "复用共享节点状态与采样；只读检查已接入证据，不调用模型或修改业务服务");
        String summary = String.valueOf(node.getOrDefault("statusReason", explanation(reason)));
        jdbc.update(
                "UPDATE observability_inspection_execution SET"
                    + " execution_status='COMPLETED',result=?,reason_code=?,summary=?,evidence_json=?,finished_at=?"
                    + " WHERE run_id=? AND target_id=? AND environment=? AND"
                    + " execution_status='RUNNING' AND lease_until>=?",
                result,
                reason,
                limit(summary),
                write(evidence),
                ts(finishedAt),
                reservation.runId(),
                node.get("ciCode"),
                node.getOrDefault("environment", "UNKNOWN"),
                ts(finishedAt));
    }

    void finish(Reservation reservation, String status, String reason, Instant now) {
        if (reservation == null || !reservation.acquired()) return;
        boolean expired = now.isAfter(reservation.deadline()) && !"CAPTURE_TIMEOUT".equals(reason);
        String finalStatus = expired ? "TIMED_OUT" : status;
        String finalReason = expired ? "LEASE_EXPIRED" : reason;
        transactions.executeWithoutResult(
                transaction -> {
                    jdbc.update(
                            "UPDATE observability_inspection_execution SET"
                                + " execution_status=?,result='UNKNOWN',reason_code=?,summary=?,finished_at=?"
                                + " WHERE run_id=? AND execution_status='RUNNING'",
                            finalStatus,
                            finalReason,
                            explanation(finalReason),
                            ts(now),
                            reservation.runId());
                    recoverExpired(now);
                    jdbc.update(
                            "UPDATE observability_inspection_plan SET"
                                + " lease_owner=NULL,lease_until=NULL,updated_at=? WHERE plan_key=?"
                                + " AND lease_owner=?",
                            ts(now),
                            PLAN,
                            reservation.runId());
                });
    }

    void recoverExpired(Instant now) {
        jdbc.update(
                "UPDATE observability_inspection_execution SET"
                    + " execution_status='TIMED_OUT',result='UNKNOWN',"
                    + "reason_code='LEASE_EXPIRED',summary=?,finished_at=?"
                    + " WHERE execution_status='RUNNING' AND lease_until<=?",
                explanation("LEASE_EXPIRED"),
                ts(now),
                ts(now));
    }

    List<Long> expiredWorkflows() {
        return jdbc.queryForList(
                "SELECT DISTINCT e.workflow_run_id FROM observability_inspection_execution e"
                        + " JOIN operations_workflow_run w ON w.id=e.workflow_run_id"
                        + " WHERE e.execution_status='TIMED_OUT' AND w.status='RUNNING'",
                Long.class);
    }

    List<Map<String, Object>> history(String code) {
        return jdbc.query(
                "SELECT * FROM observability_inspection_execution WHERE target_id=?"
                        + " ORDER BY scheduled_for DESC,id DESC LIMIT 50",
                this::row,
                code);
    }

    List<Map<String, Object>> run(String runId) {
        return jdbc.query(
                "SELECT * FROM observability_inspection_execution WHERE run_id=? ORDER BY id",
                this::row,
                runId);
    }

    Map<String, Object> schedule(boolean enabled, long intervalMs) {
        var rows =
                jdbc.query(
                        "SELECT next_run_at,lease_until FROM observability_inspection_plan WHERE"
                                + " plan_key=?",
                        this::plan,
                        PLAN);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("intervalMs", Math.max(60000, intervalMs));
        result.put(
                "nextRunAt",
                !enabled || rows.isEmpty() ? null : rows.get(0).nextRunAt().toString());
        result.put("reasonCode", enabled ? "SCHEDULE_ENABLED" : "PLAN_DISABLED");
        return result;
    }

    private Plan plan(ResultSet row, int index) throws SQLException {
        // DATETIME getObject may be LocalDateTime on MySQL. Use the driver's configured timezone,
        // matching our Timestamp writes and execution history; never reinterpret local time as UTC.
        Timestamp next = row.getTimestamp("next_run_at");
        Timestamp lease = row.getTimestamp("lease_until");
        return new Plan(next.toInstant(), lease == null ? null : lease.toInstant());
    }

    Map<String, Object> today(String environment) {
        var rows =
                jdbc.queryForList(
                        "SELECT result,execution_status,reason_code,COUNT(*) count FROM"
                            + " observability_inspection_execution WHERE"
                            + " scheduled_for>=CURRENT_DATE AND (?='ALL' OR environment=?) GROUP BY"
                            + " result,execution_status,reason_code",
                        environment,
                        environment);
        Map<String, Object> result = new LinkedHashMap<>();
        long total = 0;
        for (var row : rows) {
            String status =
                    legacyStatus(
                            String.valueOf(row.get("result")),
                            String.valueOf(row.get("execution_status")),
                            Map.of(
                                    "health",
                                    "OBSERVED_DEGRADATION".equals(row.get("reason_code"))
                                            ? "DEGRADED"
                                            : "UNKNOWN"));
            long count = ((Number) row.get("count")).longValue();
            result.put(status, ((Number) result.getOrDefault(status, 0L)).longValue() + count);
            total += count;
        }
        result.put("total", total);
        return result;
    }

    private Map<String, Object> row(ResultSet row, int index) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        var evidence = read(row.getString("evidence_json"));
        String state = row.getString("execution_status");
        String conclusion = row.getString("result");
        String runId = row.getString("run_id");
        result.put("id", row.getLong("id"));
        result.put("ciCode", row.getString("target_id"));
        result.put("targetId", row.getString("target_id"));
        result.put("checkId", row.getString("check_id"));
        result.put("environment", row.getString("environment"));
        result.put("runId", runId);
        result.put("scheduledFor", time(row.getTimestamp("scheduled_for")));
        result.put("startedAt", time(row.getTimestamp("started_at")));
        result.put("finishedAt", time(row.getTimestamp("finished_at")));
        result.put("lastCheckedAt", result.get("finishedAt"));
        Timestamp start = row.getTimestamp("started_at");
        Timestamp finish = row.getTimestamp("finished_at");
        result.put(
                "durationMs",
                start == null || finish == null
                        ? null
                        : Math.max(
                                0,
                                Duration.between(start.toInstant(), finish.toInstant())
                                        .toMillis()));
        result.put("status", legacyStatus(conclusion, state, evidence));
        result.put("executionStatus", state);
        result.put("result", conclusion);
        result.put("reasonCode", row.getString("reason_code"));
        result.put("summary", row.getString("summary"));
        result.put("source", row.getString("source"));
        result.put("executor", row.getString("executor"));
        result.put("nextRunAt", time(row.getTimestamp("next_run_at")));
        result.put("workflowRunId", row.getObject("workflow_run_id"));
        result.put("evidence", evidence);
        result.put(
                "evidenceRefs", List.of("inspection:" + runId + ":" + row.getString("target_id")));
        return result;
    }

    private static String legacyStatus(
            String conclusion, String execution, Map<String, Object> evidence) {
        if (!"COMPLETED".equals(execution)) return "UNKNOWN";
        return switch (conclusion) {
            case "PASS" -> "SUCCESS";
            case "ABNORMAL" -> "DEGRADED".equals(evidence.get("health")) ? "WARNING" : "FAILED";
            default -> "UNKNOWN";
        };
    }

    private static String explanation(String reason) {
        return switch (reason) {
            case "PLAN_DISABLED" -> "自动巡检计划已关闭，本时隙未执行；可手动检查";
            case "EXECUTOR_BUSY" -> "已有巡检或工作流执行，本次跳过；保留已有结果但不宣称本次通过";
            case "MISSED_SCHEDULE" -> "服务停机或调度延迟错过时隙；记录遗漏范围，不无限补跑";
            case "LEASE_EXPIRED" -> "执行租约已到期，可能发生进程中断；未取得完成证据";
            case "CAPTURE_TIMEOUT" -> "读取观测证据超过执行时限，本次结论未知";
            case "CAPTURE_FAILED" -> "观测证据读取失败，本次结论未知";
            case "TARGET_MISSING" -> "执行时目标未返回观测数据，本次结论未知";
            case "TARGET_INACTIVE" -> "对象已停用或退役，本次检查不适用";
            case "WORKFLOW_FAILED" -> "所属健康巡检工作流未完成，本次结论未知";
            case "EXECUTING" -> "正在读取已接入观测证据";
            default -> "检查已结束，请结合实际观测范围与证据判断";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String value) {
        try {
            return json.readValue(value, LinkedHashMap.class);
        } catch (JsonProcessingException exception) {
            return Map.of("evidenceError", "STORED_EVIDENCE_INVALID");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("巡检执行证据序列化失败", exception);
        }
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String time(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static String limit(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
