package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.OpsPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Durable intent before Nacos mutation, plus unified platform audit.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
class TrafficChangeRepository {
    record Intent(TrafficGovernanceDtos.Change change, String hash, long actorId) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final PlatformAuditRepository audit;

    TrafficChangeRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            TransactionTemplate tx,
            PlatformAuditRepository audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
        this.audit = audit;
    }

    Intent find(String requestId) {
        var items =
                jdbc.query(
                        "SELECT * FROM traffic_governance_change WHERE request_id=?",
                        (rs, row) ->
                                new Intent(
                                        map(rs),
                                        rs.getString("request_hash"),
                                        rs.getLong("actor_id")),
                        requestId);
        return items.isEmpty() ? null : items.get(0);
    }

    TrafficGovernanceDtos.Change get(long id) {
        var items =
                jdbc.query(
                        "SELECT * FROM traffic_governance_change WHERE id=?",
                        (rs, row) -> map(rs),
                        id);
        return items.isEmpty() ? null : items.get(0);
    }

    List<TrafficGovernanceDtos.Change> history(String type) {
        return jdbc.query(
                "SELECT * FROM traffic_governance_change WHERE rule_type=? ORDER BY id DESC LIMIT"
                        + " 30",
                (rs, row) -> map(rs),
                type);
    }

    Intent reserve(
            String type,
            TrafficGovernanceDtos.Publish request,
            String hash,
            JsonNode before,
            JsonNode after,
            OpsPrincipal actor,
            Long rollback) {
        return tx.execute(
                status -> {
                    jdbc.update(
                            "INSERT INTO"
                                + " traffic_governance_change(request_id,request_hash,rule_type,action,status,"
                                + "before_json,after_json,expected_revision,revision,comment,actor_id,actor_name,"
                                + "rollback_version_id,message)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            request.requestId(),
                            hash,
                            type,
                            rollback == null ? "PUBLISH" : "ROLLBACK",
                            "REQUESTED",
                            before.toString(),
                            after.toString(),
                            request.expectedRevision(),
                            NacosConfigurationClient.sha256(after.toString()),
                            request.comment().trim(),
                            actor.userId(),
                            actor.username(),
                            rollback,
                            "已记录发布意图，等待Nacos与客户端核对");
                    var intent = find(request.requestId());
                    audit.addPlatform(
                            "SENTINEL_RULE",
                            String.valueOf(intent.change().id()),
                            "TRAFFIC_RULE_REQUESTED",
                            actor.userId(),
                            json.createObjectNode()
                                    .put("ruleType", type)
                                    .put("requestId", request.requestId())
                                    .put("comment", request.comment().trim())
                                    .set("rules", after)
                                    .toString());
                    return intent;
                });
    }

    void finish(long id, String state, String message, long actorId) {
        tx.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "UPDATE traffic_governance_change SET"
                                + " status=?,message=?,finish_time=CURRENT_TIMESTAMP WHERE id=?",
                            state,
                            message,
                            id);
                    audit.addPlatform(
                            "SENTINEL_RULE",
                            String.valueOf(id),
                            "TRAFFIC_RULE_" + state,
                            actorId,
                            json.createObjectNode()
                                    .put("status", state)
                                    .put("message", message)
                                    .toString());
                });
    }

    void confirmPublished(String type, String revision) {
        // Only reconcile acknowledged publication, never infer success from an uncertain request.
        tx.executeWithoutResult(
                status -> {
                    var pending =
                            jdbc.query(
                                    "SELECT * FROM traffic_governance_change WHERE rule_type=? AND"
                                            + " revision=? AND status='PUBLISHED' ORDER BY id DESC"
                                            + " LIMIT 1 FOR UPDATE",
                                    (rs, row) ->
                                            new Intent(
                                                    map(rs),
                                                    rs.getString("request_hash"),
                                                    rs.getLong("actor_id")),
                                    type,
                                    revision);
                    if (!pending.isEmpty())
                        finish(
                                pending.get(0).change().id(),
                                "APPLIED",
                                "Nacos持久化版本与客户端加载规则已在后续核对中确认一致。",
                                pending.get(0).actorId());
                });
    }

    private TrafficGovernanceDtos.Change map(ResultSet rs) throws SQLException {
        var finished = rs.getTimestamp("finish_time");
        return new TrafficGovernanceDtos.Change(
                rs.getLong("id"),
                rs.getString("rule_type"),
                rs.getString("action"),
                rs.getString("status"),
                parse(rs.getString("before_json")),
                parse(rs.getString("after_json")),
                rs.getString("expected_revision"),
                rs.getString("revision"),
                rs.getString("comment"),
                rs.getString("actor_name"),
                rs.getTimestamp("create_time").toInstant(),
                finished == null ? null : finished.toInstant(),
                rs.getObject("rollback_version_id") == null
                        ? null
                        : rs.getLong("rollback_version_id"),
                rs.getString("message"));
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (Exception ignored) {
            throw new IllegalStateException("Traffic history invalid");
        }
    }
}
