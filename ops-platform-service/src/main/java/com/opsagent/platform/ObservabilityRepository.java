package com.opsagent.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.OpsPrincipal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 保存共享拓扑布局与追加式巡检快照，演练详情仍按账号隔离。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
class ObservabilityRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ObservabilityRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Set<String> drilling() {
        return Set.copyOf(jdbc.queryForList("SELECT DISTINCT target_code FROM operations_demo_incident"
                + " WHERE status IN ('INJECTING','FAULT_ACTIVE','INJECTION_UNCONFIRMED','RECOVERING')"
                + " AND expires_at>CURRENT_TIMESTAMP(3)",
                String.class));
    }

    Map<String, Object> layout(String environment) {
        var rows = jdbc.queryForList("SELECT positions_json FROM observability_topology_layout WHERE environment=?",
                environment);
        if (rows.isEmpty()) return Map.of();
        return readMap(String.valueOf(rows.get(0).get("positions_json")));
    }

    void layout(ObservabilityDtos.Layout request, long actorId) {
        Map<String, Object> positions = new LinkedHashMap<>();
        request.positions().forEach(position -> positions.put(position.ciCode(),
                Map.of("x", position.x(), "y", position.y())));
        jdbc.update("INSERT INTO observability_topology_layout(environment,positions_json,actor_id) VALUES(?,?,?)"
                + " ON DUPLICATE KEY UPDATE positions_json=VALUES(positions_json),actor_id=VALUES(actor_id),"
                + "updated_at=CURRENT_TIMESTAMP(3)", request.environment(), write(positions), actorId);
    }

    List<Map<String, Object>> recentRuns(String code, OpsPrincipal actor) {
        return jdbc.query("SELECT incident_id,status,started_at,recovered_at FROM operations_demo_incident"
                + " WHERE target_code=? AND (?=1 OR owner_id=?) ORDER BY started_at DESC LIMIT 5", (row, index) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", row.getString("incident_id"));
                    result.put("title", "本服务的真实演练与恢复记录");
                    result.put("status", row.getString("status"));
                    result.put("startedAt", instant(row.getTimestamp("started_at")));
                    result.put("finishedAt", instant(row.getTimestamp("recovered_at")));
                    result.put("source", "DEMO_INCIDENT");
                    return result;
                }, code, actor.roles().contains("ADMIN") ? 1 : 0, actor.userId());
    }

    List<Map<String, Object>> recentChanges(String code, OpsPrincipal actor) {
        if (!DemoTargetDtos.TARGET.equals(code) || !actor.roles().contains("ADMIN")) return List.of();
        return jdbc.query("SELECT id,configuration_id,status,actor_name,created_at,result_revision revision"
                + " FROM operations_managed_config_change ORDER BY id DESC LIMIT 5", (row, index) -> Map.of(
                        "id", row.getLong("id"), "title", row.getString("configuration_id"),
                        "status", row.getString("status"), "actor", row.getString("actor_name"),
                        "occurredAt", instant(row.getTimestamp("created_at")),
                        "revision", row.getString("revision") == null ? "" : row.getString("revision")));
    }

    void inspection(Map<String, Object> node, long duration, long actorId, String source, Long workflowRunId) {
        String health = String.valueOf(node.get("health"));
        String status = switch (health) {
            case "HEALTHY" -> "SUCCESS";
            case "CRITICAL" -> "FAILED";
            case "DEGRADED" -> "WARNING";
            default -> "UNKNOWN";
        };
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("health", health);
        evidence.put("metrics", node.get("metrics"));
        evidence.put("observedAt", node.get("observedAt"));
        evidence.put("activeAlertCount", node.get("activeAlertCount"));
        evidence.put("workflowRunId", workflowRunId);
        evidence.put("coverage", "Prometheus实例/RED/JVM与Alertmanager；未逐项探测DB、注册或LLM连接");
        jdbc.update("INSERT INTO observability_inspection_result(ci_code,status,summary,evidence_json,checked_at,"
                + "duration_ms,actor_id,source) VALUES(?,?,?,?,?,?,?,?)", node.get("ciCode"), status,
                node.get("statusReason"), write(evidence), Timestamp.from(Instant.now()), duration, actorId, source);
    }

    List<Map<String, Object>> history(String code) {
        return jdbc.query("SELECT * FROM observability_inspection_result WHERE ci_code=? ORDER BY id DESC LIMIT 50",
                (row, index) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", row.getLong("id"));
                    result.put("ciCode", code);
                    result.put("status", row.getString("status"));
                    result.put("summary", row.getString("summary"));
                    result.put("lastCheckedAt", instant(row.getTimestamp("checked_at")));
                    result.put("durationMs", row.getLong("duration_ms"));
                    result.put("source", row.getString("source"));
                    result.put("evidence", readMap(row.getString("evidence_json")));
                    return result;
                }, code);
    }

    Map<String, Object> today() {
        return today("ALL");
    }

    Map<String, Object> today(String environment) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status,COUNT(*) count"
                + " FROM observability_inspection_result WHERE checked_at>=CURRENT_DATE"
                + " AND (?='ALL' OR ci_code IN (SELECT ci_code FROM cmdb_ci WHERE environment=?)) GROUP BY status",
                environment, environment);
        Map<String, Object> counts = new LinkedHashMap<>();
        long total = 0;
        for (var row : rows) {
            long count = ((Number) row.get("count")).longValue();
            total += count;
            counts.put(String.valueOf(row.get("status")), count);
        }
        counts.put("total", total);
        return counts;
    }

    long consecutiveFailures(String code) {
        Long result = jdbc.queryForObject("SELECT COUNT(*) FROM observability_inspection_result WHERE ci_code=?"
                + " AND status='FAILED' AND id>COALESCE((SELECT MAX(id) FROM observability_inspection_result"
                + " WHERE ci_code=? AND status<>'FAILED'),0)", Long.class, code, code);
        return result == null ? 0 : result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String text) {
        try {
            return json.readValue(text, LinkedHashMap.class);
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("观测快照序列化失败", exception);
        }
    }

    private String instant(Timestamp value) {
        return value == null ? "" : value.toInstant().toString();
    }
}
