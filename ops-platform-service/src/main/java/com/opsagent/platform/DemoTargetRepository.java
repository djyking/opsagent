package com.opsagent.platform;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 真实目标演练、唯一活动租约和恢复动作幂等记录；HTTP 调用不占据数据库事务。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
public class DemoTargetRepository {
    private static final RowMapper<DemoTargetDtos.Incident> MAPPER =
            (row, index) ->
                    new DemoTargetDtos.Incident(
                            row.getString("incident_id"),
                            row.getString("target_code"),
                            row.getString("scenario_code"),
                            row.getLong("owner_id"),
                            row.getString("owner_name"),
                            row.getString("owner_kind"),
                            row.getString("status"),
                            row.getString("expected_revision"),
                            time(row, "started_at"),
                            time(row, "expires_at"),
                            time(row, "recovered_at"),
                            row.getString("recovery_source"),
                            row.getInt("last_http_status"),
                            row.getString("last_reason"),
                            time(row, "last_observed_at"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    DemoTargetRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    DemoTargetDtos.Incident create(
            DemoTargetDtos.CreateScenario request, InternalActorTokens.Context actor) {
        String targetCode = DemoTargetDtos.scenarioTarget(request.scenarioCode());
        if (!targetCode.equals(actor.targetCode())) throw conflict("DEMO_TARGET_MISMATCH");
        return transactions.execute(
                transaction -> {
                    jdbc.update(
                            "INSERT IGNORE INTO operations_demo_target_lease(target_code)"
                                    + " VALUES(?)",
                            targetCode);
                    Map<String, Object> lease =
                            jdbc.queryForMap(
                                    "SELECT active_incident,available_after FROM"
                                        + " operations_demo_target_lease WHERE target_code=? FOR"
                                        + " UPDATE",
                                    targetCode);
                    if (lease.get("active_incident") != null) throw conflict("TARGET_BUSY");
                    if (configurationBusy(targetCode)) throw conflict("TARGET_CONFIGURATION_BUSY");
                    Timestamp available = (Timestamp) lease.get("available_after");
                    if (available != null && available.toInstant().isAfter(Instant.now()))
                        throw conflict("TARGET_COOLDOWN");
                    Long recent =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM operations_demo_incident"
                                            + " WHERE owner_id=? AND started_at>?",
                                    Long.class,
                                    actor.userId(),
                                    Timestamp.from(Instant.now().minusSeconds(3600)));
                    if (recent != null && recent >= 6) throw conflict("DEMO_HOURLY_LIMIT");
                    String id = UUID.randomUUID().toString();
                    Instant now = Instant.now();
                    int ttl = request.ttlSeconds() == null ? 600 : request.ttlSeconds();
                    Instant expires = now.plusSeconds(ttl);
                    jdbc.update(
                            "INSERT INTO"
                                + " operations_demo_incident(incident_id,target_code,scenario_code,"
                                + "owner_id,owner_name,owner_kind,status,started_at,expires_at)"
                                + " VALUES(?,?,?,?,?,?,'INJECTING',?,?)",
                            id,
                            targetCode,
                            request.scenarioCode(),
                            actor.userId(),
                            actor.username(),
                            actor.roles().contains("DEMO") ? "DEMO" : "USER",
                            Timestamp.from(now),
                            Timestamp.from(expires));
                    jdbc.update(
                            "UPDATE operations_demo_target_lease SET active_incident=? WHERE"
                                    + " target_code=?",
                            id,
                            targetCode);
                    return get(id);
                });
    }

    List<DemoTargetDtos.Incident> page(long actorId, boolean all) {
        return page(actorId, all, DemoTargetDtos.TARGET);
    }

    List<DemoTargetDtos.Incident> page(long actorId, boolean all, String targetCode) {
        return all
                ? jdbc.query(
                        "SELECT * FROM operations_demo_incident WHERE target_code=? ORDER BY"
                            + " started_at DESC LIMIT 30",
                        MAPPER,
                        targetCode)
                : jdbc.query(
                        "SELECT * FROM operations_demo_incident WHERE target_code=? AND owner_id=?"
                                + " ORDER BY started_at DESC LIMIT 30",
                        MAPPER,
                        targetCode,
                        actorId);
    }

    DemoTargetDtos.Incident get(String id) {
        return jdbc
                .query("SELECT * FROM operations_demo_incident WHERE incident_id=?", MAPPER, id)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.NOT_FOUND, "DEMO_INCIDENT_NOT_FOUND"));
    }

    DemoTargetDtos.Incident active() {
        return active(DemoTargetDtos.TARGET);
    }

    DemoTargetDtos.Incident active(String targetCode) {
        return jdbc
                .query(
                        "SELECT i.* FROM operations_demo_incident i JOIN"
                            + " operations_demo_target_lease l ON l.active_incident=i.incident_id"
                            + " WHERE l.target_code=?",
                        MAPPER,
                        targetCode)
                .stream()
                .findFirst()
                .orElse(null);
    }

    Instant availableAfter() {
        return availableAfter(DemoTargetDtos.TARGET);
    }

    Instant availableAfter(String targetCode) {
        var rows =
                jdbc.query(
                        "SELECT available_after FROM operations_demo_target_lease WHERE"
                                + " target_code=?",
                        (row, index) -> time(row, "available_after"),
                        targetCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    boolean configurationBusy() {
        return configurationBusy(DemoTargetDtos.TARGET);
    }

    boolean configurationBusy(String targetCode) {
        Long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM operations_managed_config_guard"
                                + " WHERE target_code=? AND expires_at>?",
                        Long.class,
                        targetCode,
                        Timestamp.from(Instant.now()));
        return count != null && count > 0;
    }

    void observed(
            String id,
            String revision,
            int httpStatus,
            String reason,
            String source,
            Instant observed) {
        jdbc.update(
                "UPDATE operations_demo_incident SET"
                    + " expected_revision=?,last_http_status=?,last_reason=?,last_observed_at=?,status=CASE"
                    + " WHEN status='INJECTING' THEN 'FAULT_ACTIVE' ELSE status END WHERE"
                    + " incident_id=?",
                revision,
                httpStatus,
                reason,
                Timestamp.from(observed),
                id);
        if (httpStatus == 200 && List.of("AGENT_TOOL", "MANUAL", "TTL_GUARD").contains(source)) {
            transactions.executeWithoutResult(
                    transaction -> {
                        jdbc.update(
                                "UPDATE operations_demo_incident SET"
                                        + " status=?,recovery_source=?,recovered_at=? WHERE"
                                        + " incident_id=? AND recovered_at IS NULL",
                                "TTL_GUARD".equals(source) ? "EXPIRED_RECOVERED" : "RECOVERED",
                                source,
                                Timestamp.from(observed),
                                id);
                        jdbc.update(
                                "UPDATE operations_demo_target_lease SET"
                                    + " active_incident=NULL,available_after=? WHERE target_code=?"
                                    + " AND active_incident=?",
                                Timestamp.from(observed.plusSeconds(60)),
                                get(id).targetCode(),
                                id);
                    });
        }
    }

    void injectionUncertain(String id) {
        jdbc.update(
                "UPDATE operations_demo_incident SET status='INJECTION_UNCONFIRMED' WHERE"
                        + " incident_id=?",
                id);
    }

    void releaseExpiredUnapplied(String id, Instant observed) {
        transactions.executeWithoutResult(
                transaction -> {
                    int changed =
                            jdbc.update(
                                    "UPDATE operations_demo_incident SET"
                                        + " status='EXPIRED_NOT_APPLIED',"
                                        + "recovered_at=?,recovery_source='NOT_APPLIED',last_http_status=200"
                                        + " WHERE incident_id=? AND expires_at<? AND"
                                        + " expected_revision=''",
                                    Timestamp.from(observed),
                                    id,
                                    Timestamp.from(observed));
                    if (changed > 0) {
                        jdbc.update(
                                "UPDATE operations_demo_target_lease SET"
                                    + " active_incident=NULL,available_after=? WHERE target_code=?"
                                    + " AND active_incident=?",
                                Timestamp.from(observed.plusSeconds(60)),
                                get(id).targetCode(),
                                id);
                    }
                });
    }

    Map<String, Object> reserveAction(
            DemoTargetDtos.Action action, InternalActorTokens.Context actor) {
        return transactions.execute(
                transaction -> {
                    jdbc.queryForMap(
                            "SELECT target_code FROM operations_demo_target_lease WHERE"
                                    + " target_code=? FOR UPDATE",
                            actor.targetCode());
                    List<Map<String, Object>> existing =
                            jdbc.queryForList(
                                    "SELECT * FROM operations_demo_action WHERE idempotency_key=?",
                                    action.idempotencyKey());
                    if (!existing.isEmpty()) {
                        Map<String, Object> row = existing.get(0);
                        if (!action.incidentId().equals(row.get("incident_id"))
                                || !action.action().equals(row.get("action_code"))
                                || !action.expectedRevision().equals(row.get("expected_revision"))
                                || ((Number) row.get("actor_id")).longValue() != actor.userId()
                                || !actor.runId().equals(row.get("run_id")))
                            throw conflict("IDEMPOTENCY_CONFLICT");
                        return row;
                    }
                    jdbc.update(
                            "INSERT INTO"
                                + " operations_demo_action(idempotency_key,incident_id,action_code,"
                                + "expected_revision,actor_id,run_id,status,created_at)"
                                + " VALUES(?,?,?,?,?,?,'REQUESTED',?)",
                            action.idempotencyKey(),
                            action.incidentId(),
                            action.action(),
                            action.expectedRevision(),
                            actor.userId(),
                            actor.runId(),
                            Timestamp.from(Instant.now()));
                    return Map.of();
                });
    }

    void actionResult(String key, String status, String result) {
        jdbc.update(
                "UPDATE operations_demo_action SET status=?,result_json=?,finished_at=? WHERE"
                        + " idempotency_key=?",
                status,
                result,
                Timestamp.from(Instant.now()),
                key);
    }

    DemoTargetDtos.IncidentOwner owner(Instant startsAt) {
        return owner(DemoTargetDtos.TARGET, startsAt);
    }

    DemoTargetDtos.IncidentOwner owner(String targetCode, Instant startsAt) {
        var matches =
                jdbc
                        .query(
                                "SELECT * FROM operations_demo_incident WHERE target_code=? AND"
                                    + " started_at<=? AND expires_at>=? ORDER BY started_at DESC"
                                    + " LIMIT 2",
                                MAPPER,
                                targetCode,
                                Timestamp.from(startsAt.plusSeconds(2)),
                                Timestamp.from(startsAt.minusSeconds(45)))
                        .stream()
                        .filter(
                                row ->
                                        row.recoveredAt() == null
                                                || !startsAt.isAfter(
                                                        row.recoveredAt().plusSeconds(45)))
                        .toList();
        if (matches.size() != 1)
            throw new BusinessException(ErrorCode.NOT_FOUND, "DEMO_EPISODE_OWNER_UNKNOWN");
        var row = matches.get(0);
        return new DemoTargetDtos.IncidentOwner(
                row.incidentId(),
                row.ownerId(),
                row.ownerName(),
                row.ownerKind(),
                row.scenarioCode(),
                row.startedAt(),
                row.expiresAt());
    }

    private static Instant time(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private BusinessException conflict(String reason) {
        return new BusinessException(ErrorCode.CONFLICT, reason);
    }
}
