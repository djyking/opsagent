package com.opsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded recovery-only watches reuse read-only node evidence without changing full inspections.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class EventRecoveryObservationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TopologyAggregationService topology;
    private final long lifetime;
    private final long interval;

    EventRecoveryObservationService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            TopologyAggregationService topology,
            @Value("${ops.event.recovery.watch-lifetime-seconds:3600}") long lifetime,
            @Value("${ops.event.recovery.sample-interval-seconds:30}") long interval) {
        if (lifetime < 180 || lifetime > 86400 || interval < 10 || interval > 300)
            throw new IllegalArgumentException("恢复观察任务的有效期或采样间隔无效");
        this.jdbc = jdbc;
        this.json = json;
        this.topology = topology;
        this.lifetime = lifetime;
        this.interval = interval;
    }

    @PostConstruct
    void initialize() {
        if (jdbc.getDataSource() != null)
            new ResourceDatabasePopulator(
                            new ClassPathResource("observability-recovery-schema.sql"))
                    .execute(jdbc.getDataSource());
    }

    @Transactional
    void start(long ticketId, String code, String environment, Instant resultAt, long actor) {
        Instant now = Instant.now();
        if (ticketId <= 0 || resultAt == null || resultAt.isAfter(now.plusSeconds(2)))
            throw new BusinessException(ErrorCode.VALIDATION, "恢复观察身份或处理结果时间无效");
        var node = topology.recoveryNode(code);
        if (!environment.equals(node.get("environment")))
            throw new BusinessException(ErrorCode.CONFLICT, "恢复观察环境与实际目标不匹配");
        // Same handling result extends a bounded watch; it never rewrites or clears old samples.
        var existing =
                jdbc.query(
                        "SELECT result_at FROM observability_recovery_watch WHERE ticket_id=? FOR"
                                + " UPDATE",
                        (row, index) -> row.getTimestamp("result_at").toInstant(),
                        ticketId);
        if (!existing.isEmpty() && existing.get(0).isAfter(resultAt))
            throw new BusinessException(ErrorCode.CONFLICT, "已有更新的处理结果，旧恢复观察请求失效");
        Long active =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM observability_recovery_watch WHERE active=TRUE AND"
                                + " expires_at>?",
                        Long.class,
                        Timestamp.from(now));
        if (existing.isEmpty() && active != null && active >= 32)
            throw new BusinessException(ErrorCode.CONFLICT, "恢复观察并发上限已达到，请稍后重试");
        jdbc.update(
                "INSERT INTO"
                        + " observability_recovery_watch(ticket_id,ci_code,environment,result_at,"
                        + "actor_id,expires_at,active)"
                        + " VALUES(?,?,?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE"
                        + " ci_code=VALUES(ci_code),environment=VALUES(environment),"
                        + " result_at=VALUES(result_at),actor_id=VALUES(actor_id),"
                        + "expires_at=VALUES(expires_at),active=TRUE",
                ticketId,
                code,
                environment,
                Timestamp.from(resultAt),
                actor,
                Timestamp.from(now.plusSeconds(lifetime)));
    }

    void stop(long ticketId, String code, String environment) {
        jdbc.update(
                "UPDATE observability_recovery_watch SET active=FALSE WHERE ticket_id=? AND"
                        + " ci_code=? AND environment=?",
                ticketId,
                code,
                environment);
    }

    @Scheduled(
            fixedDelayString = "${ops.event.recovery.sample-interval-seconds:30}000",
            initialDelay = 30000)
    void tick() {
        Instant now = Instant.now();
        var watches =
                jdbc.queryForList(
                        "SELECT ticket_id,ci_code,environment,result_at FROM"
                                + " observability_recovery_watch WHERE active=TRUE AND expires_at>?"
                                + " ORDER BY updated_at DESC LIMIT 32",
                        Timestamp.from(now));
        for (var watch : watches) {
            try {
                capture(watch, Instant.now());
            } catch (RuntimeException unavailable) {
                // A missing persisted check remains a gap. Never synthesize a successful result.
            }
        }
        jdbc.update(
                "DELETE FROM observability_recovery_sample WHERE checked_at<?",
                Timestamp.from(now.minusSeconds(72 * 3600)));
        jdbc.update(
                "UPDATE observability_recovery_watch SET active=FALSE WHERE active=TRUE AND"
                        + " expires_at<=?",
                Timestamp.from(now));
    }

    void capture(Map<String, Object> watch, Instant now) {
        Long recent =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM observability_recovery_sample WHERE ticket_id=? AND"
                                + " result_at=? AND checked_at>?",
                        Long.class,
                        watch.get("ticket_id"),
                        watch.get("result_at"),
                        Timestamp.from(now.minusSeconds(interval)));
        if (recent != null && recent > 0) return;
        Map<String, Object> evidence = new LinkedHashMap<>();
        String result = "UNKNOWN";
        try {
            var node = topology.recoveryNode(String.valueOf(watch.get("ci_code")));
            if (watch.get("environment").equals(node.get("environment"))) {
                for (String key :
                        List.of(
                                "ciCode",
                                "environment",
                                "identity",
                                "health",
                                "healthScope",
                                "healthReasonCode",
                                "observedAt",
                                "activeAlertCount",
                                "observation",
                                "metricEvidence",
                                "evidenceRefs"))
                    if (node.containsKey(key)) evidence.put(key, node.get(key));
                result = "HEALTHY".equals(node.get("health")) ? "PASS" : "ABNORMAL";
            }
        } catch (RuntimeException unavailable) {
            evidence.put("failureReason", "CURRENT_EVIDENCE_UNAVAILABLE");
        }
        try {
            jdbc.update(
                    "INSERT INTO"
                        + " observability_recovery_sample(ticket_id,ci_code,environment,result_at,"
                        + " checked_at,result,evidence_json) VALUES(?,?,?,?,?,?,?)",
                    watch.get("ticket_id"),
                    watch.get("ci_code"),
                    watch.get("environment"),
                    watch.get("result_at"),
                    Timestamp.from(Instant.now()),
                    result,
                    json.writeValueAsString(evidence));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("恢复观察证据不可序列化", invalid);
        }
    }

    List<Map<String, Object>> history(long ticketId, String code, String environment) {
        return jdbc.query(
                "SELECT s.* FROM observability_recovery_sample s JOIN observability_recovery_watch"
                    + " w ON w.ticket_id=s.ticket_id AND w.result_at=s.result_at AND"
                    + " w.ci_code=s.ci_code AND w.environment=s.environment WHERE s.ticket_id=? AND"
                    + " s.ci_code=? AND s.environment=? ORDER BY s.checked_at DESC,s.id DESC LIMIT"
                    + " 100",
                (row, index) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("ciCode", code);
                    item.put("environment", environment);
                    item.put("runId", "recovery:" + row.getLong("id"));
                    item.put("ticketId", row.getLong("ticket_id"));
                    item.put("resultAt", row.getTimestamp("result_at").toInstant().toString());
                    item.put(
                            "lastCheckedAt", row.getTimestamp("checked_at").toInstant().toString());
                    item.put("executionStatus", "COMPLETED");
                    item.put("source", "RECOVERY_WATCH");
                    item.put("result", row.getString("result"));
                    try {
                        item.put(
                                "evidence",
                                json.readValue(row.getString("evidence_json"), Map.class));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                        item.put("evidence", Map.of());
                    }
                    return item;
                },
                ticketId,
                code,
                environment);
    }
}
