package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * 配置发布意图、历史与审计持久化，复用演练租约行锁避免跨实例同时变更。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
class ManagedConfigurationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final PlatformAuditRepository audit;

    ManagedConfigurationRepository(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            ObjectMapper json,
            PlatformAuditRepository audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.audit = audit;
    }

    ManagedConfigurationDtos.History existing(String requestId, String hash, long actorId) {
        var rows =
                jdbc.queryForList(
                        "SELECT id,request_hash,actor_id FROM operations_managed_config_change"
                                + " WHERE request_id=?",
                        requestId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        if (!hash.equals(row.get("request_hash"))
                || ((Number) row.get("actor_id")).longValue() != actorId) {
            throw conflict("发布请求标识已用于其他内容，请刷新后重试");
        }
        return get(((Number) row.get("id")).longValue());
    }

    ManagedConfigurationDtos.History reserve(
            ManagedConfigurationDtos.Publish request,
            JsonNode before,
            String action,
            Long rollbackId,
            String hash,
            OpsPrincipal actor) {
        return tx.execute(
                status -> {
                    jdbc.update(
                            "INSERT IGNORE INTO operations_demo_target_lease(target_code)"
                                    + " VALUES(?)",
                            DemoTargetDtos.TARGET);
                    Map<String, Object> lease =
                            jdbc.queryForMap(
                                    "SELECT * FROM operations_demo_target_lease WHERE target_code=?"
                                            + " FOR UPDATE",
                                    DemoTargetDtos.TARGET);
                    var existing = existing(request.requestId(), hash, actor.userId());
                    if (existing != null) return existing;
                    Timestamp available = (Timestamp) lease.get("available_after");
                    if (lease.get("active_incident") != null
                            || available != null && available.toInstant().isAfter(Instant.now())) {
                        throw conflict("目标正在演练或冷却，当前不能发布配置");
                    }
                    Long busy =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM operations_managed_config_guard WHERE"
                                            + " target_code=? AND expires_at>?",
                                    Long.class,
                                    DemoTargetDtos.TARGET,
                                    Timestamp.from(Instant.now()));
                    if (busy != null && busy > 0) throw conflict("另一个配置发布仍在确认，请稍后刷新");
                    jdbc.update(
                            "DELETE FROM operations_managed_config_guard WHERE target_code=?",
                            DemoTargetDtos.TARGET);
                    jdbc.update(
                            "INSERT INTO"
                                + " operations_managed_config_guard(target_code,request_id,expires_at)"
                                + " VALUES(?,?,?)",
                            DemoTargetDtos.TARGET,
                            request.requestId(),
                            Timestamp.from(Instant.now().plusSeconds(120)));
                    Long historyCount =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM operations_managed_config_change WHERE"
                                            + " configuration_id='order-business'",
                                    Long.class);
                    if (historyCount != null && historyCount == 0) {
                        jdbc.update(
                                "INSERT INTO"
                                    + " operations_managed_config_change(configuration_id,request_id,"
                                    + "request_hash,action,status,expected_revision,result_revision,"
                                    + "content_json,previous_json,actor_id,actor_name,comment,created_at,finished_at)"
                                    + " VALUES('order-business',?,?,'BASELINE','APPLIED',?,?,?,?,0,'系统基线',?,?,?)",
                                java.util.UUID.randomUUID().toString(),
                                hash(before.toString()),
                                request.expectedRevision(),
                                request.expectedRevision(),
                                before.toString(),
                                before.toString(),
                                "首次纳管时记录的实际Nacos版本",
                                Timestamp.from(Instant.now()),
                                Timestamp.from(Instant.now()));
                    }
                    jdbc.update(
                            "INSERT INTO"
                                + " operations_managed_config_change(configuration_id,request_id,"
                                + "request_hash,action,status,expected_revision,content_json,previous_json,"
                                + "actor_id,actor_name,comment,rollback_version_id,created_at)"
                                + " VALUES('order-business',?,?,?,'REQUESTED',?,?,?,?,?,?,?,?)",
                            request.requestId(),
                            hash,
                            action,
                            request.expectedRevision(),
                            request.content().toString(),
                            before.toString(),
                            actor.userId(),
                            actor.username(),
                            request.comment(),
                            rollbackId,
                            Timestamp.from(Instant.now()));
                    var entry = existing(request.requestId(), hash, actor.userId());
                    audit.addPlatform(
                            "MANAGED_CONFIGURATION",
                            "order-business",
                            "NACOS_CONFIG_REQUESTED",
                            actor.userId(),
                            jsonString(
                                    Map.of(
                                            "versionId",
                                            entry.id(),
                                            "action",
                                            action,
                                            "comment",
                                            request.comment())));
                    return entry;
                });
    }

    void complete(
            long id,
            String requestId,
            String status,
            String revision,
            String message,
            long actorId) {
        tx.executeWithoutResult(
                transaction -> {
                    int changed =
                            jdbc.update(
                                    "UPDATE operations_managed_config_change SET"
                                            + " status=?,result_revision=?,message=?,finished_at=?"
                                            + " WHERE id=? AND status='REQUESTED'",
                                    status,
                                    revision,
                                    message,
                                    Timestamp.from(Instant.now()),
                                    id);
                    if (changed > 0)
                        audit.addPlatform(
                                "MANAGED_CONFIGURATION",
                                "order-business",
                                "NACOS_CONFIG_" + status,
                                actorId,
                                jsonString(
                                        Map.of(
                                                "versionId",
                                                id,
                                                "revision",
                                                revision,
                                                "message",
                                                message)));
                    if (status.equals("UNCONFIRMED")) {
                        jdbc.update(
                                "UPDATE operations_managed_config_guard SET expires_at=? WHERE"
                                        + " target_code=? AND request_id=?",
                                Timestamp.from(Instant.now().plusSeconds(30)),
                                DemoTargetDtos.TARGET,
                                requestId);
                    } else {
                        jdbc.update(
                                "DELETE FROM operations_managed_config_guard WHERE target_code=?"
                                        + " AND request_id=?",
                                DemoTargetDtos.TARGET,
                                requestId);
                    }
                });
    }

    void reconcile(String publicationId, String revision, JsonNode content) {
        if (!publicationId.matches("[a-f0-9-]{36}") || !revision.matches("[a-f0-9]{64}")) return;
        tx.executeWithoutResult(
                transaction -> {
                    var rows =
                            jdbc.queryForList(
                                    "SELECT id,content_json,actor_id FROM"
                                        + " operations_managed_config_change WHERE request_id=? AND"
                                        + " status IN ('REQUESTED','UNCONFIRMED','PUBLISHED')",
                                    publicationId);
                    if (rows.isEmpty()) return;
                    Map<String, Object> row = rows.get(0);
                    if (!read(row.get("content_json").toString()).equals(content)) return;
                    long id = ((Number) row.get("id")).longValue();
                    int changed =
                            jdbc.update(
                                    "UPDATE operations_managed_config_change SET"
                                        + " status='APPLIED',result_revision=?,message=?,finished_at=?"
                                        + " WHERE id=? AND status IN"
                                        + " ('REQUESTED','UNCONFIRMED','PUBLISHED')",
                                    revision,
                                    "后续核验Nacos与目标应用版本一致，已确认生效",
                                    Timestamp.from(Instant.now()),
                                    id);
                    if (changed > 0)
                        audit.addPlatform(
                                "MANAGED_CONFIGURATION",
                                "order-business",
                                "NACOS_CONFIG_RECONCILED",
                                ((Number) row.get("actor_id")).longValue(),
                                jsonString(Map.of("versionId", id, "revision", revision)));
                    jdbc.update(
                            "DELETE FROM operations_managed_config_guard WHERE target_code=? AND"
                                    + " request_id=?",
                            DemoTargetDtos.TARGET,
                            publicationId);
                });
    }

    ManagedConfigurationDtos.HistoryPage history(String id, int page, int size) {
        var items =
                jdbc.query(
                        "SELECT * FROM operations_managed_config_change WHERE configuration_id=?"
                                + " ORDER BY id DESC LIMIT ? OFFSET ?",
                        this::map,
                        id,
                        size,
                        (page - 1) * size);
        long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM operations_managed_config_change WHERE"
                                + " configuration_id=?",
                        Long.class,
                        id);
        return new ManagedConfigurationDtos.HistoryPage(items, total, page, size);
    }

    ManagedConfigurationDtos.History get(long id) {
        return jdbc
                .query(
                        "SELECT * FROM operations_managed_config_change WHERE id=? AND"
                                + " configuration_id='order-business'",
                        this::map,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "配置历史版本不存在"));
    }

    ManagedConfigurationDtos.History byRequestId(String requestId) {
        return jdbc
                .query(
                        "SELECT * FROM operations_managed_config_change WHERE request_id=? AND"
                                + " configuration_id='order-business'",
                        this::map,
                        requestId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private ManagedConfigurationDtos.History map(ResultSet row, int index) throws SQLException {
        Timestamp finished = row.getTimestamp("finished_at");
        return new ManagedConfigurationDtos.History(
                row.getLong("id"),
                row.getLong("id"),
                row.getString("action"),
                row.getString("status"),
                read(row.getString("content_json")),
                read(row.getString("previous_json")),
                row.getString("result_revision"),
                row.getString("expected_revision"),
                row.getString("comment"),
                row.getString("actor_name"),
                row.getTimestamp("created_at").toInstant(),
                finished == null ? null : finished.toInstant(),
                (Long) row.getObject("rollback_version_id"),
                row.getString("message"));
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HASH_UNAVAILABLE");
        }
    }

    private String jsonString(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("CONFIG_AUDIT_SERIALIZATION_FAILED");
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("CONFIG_HISTORY_INVALID");
        }
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
