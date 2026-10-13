package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录实际业务观测与应用配置变更；时间相近只代表关联线索，不自动判定根因。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
class DemoTargetEvidenceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    DemoTargetEvidenceRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void capture(String targetCode, ObjectNode raw, Instant observedAt) {
        ObjectNode safe = safeSnapshot(raw);
        String incident = raw.path("incidentId").asText();
        String revision = raw.path("appliedRevision").asText();
        int status = raw.path("business").path("httpStatus").asInt();
        String reason = raw.path("business").path("reasonCode").asText();
        String recovery = raw.path("recoverySource").asText();
        var last =
                jdbc.queryForList(
                        "SELECT observed_at,http_status,reason_code,revision,incident_id FROM"
                            + " operations_demo_observation WHERE target_code=? ORDER BY id DESC"
                            + " LIMIT 1",
                        targetCode);
        boolean unchanged =
                !last.isEmpty()
                        && ((Number) last.get(0).get("http_status")).intValue() == status
                        && reason.equals(last.get(0).get("reason_code"))
                        && revision.equals(last.get(0).get("revision"))
                        && incident.equals(last.get(0).get("incident_id"))
                        && ((Timestamp) last.get(0).get("observed_at"))
                                .toInstant()
                                .isAfter(observedAt.minusSeconds(30));
        if (!unchanged) {
            jdbc.update(
                    "INSERT INTO"
                        + " operations_demo_observation(target_code,incident_id,observed_at,"
                        + "http_status,reason_code,recovery_source,revision,snapshot_json)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                    targetCode,
                    incident,
                    Timestamp.from(observedAt),
                    status,
                    reason,
                    recovery,
                    revision,
                    safe.toString());
        }
        JsonNode after = raw.path("configuration");
        JsonNode before = raw.path("previousConfiguration");
        if (!after.isObject() || !revision.matches("[a-f0-9]{64}")) return;
        Instant appliedAt;
        try {
            appliedAt = Instant.parse(raw.path("configurationAppliedAt").asText());
        } catch (Exception ignored) {
            return;
        }
        if (appliedAt.isAfter(observedAt.plusSeconds(2))) return;
        jdbc.update(
                "INSERT IGNORE INTO"
                    + " operations_demo_runtime_change(target_code,revision,occurred_at,"
                    + "observed_at,kind,before_json,after_json)"
                    + " VALUES(?,?,?,?,?,?,?)",
                targetCode,
                revision,
                Timestamp.from(appliedAt),
                Timestamp.from(observedAt),
                before.isObject() && !before.isEmpty()
                        ? "RUNTIME_CONFIGURATION_APPLIED"
                        : "RUNTIME_STATE_OBSERVED",
                before.isObject() ? before.toString() : "{}",
                after.toString());
    }

    List<Map<String, Object>> observations(DemoTargetDtos.Incident incident) {
        return jdbc.query(
                "SELECT * FROM operations_demo_observation WHERE target_code=? AND incident_id=?"
                        + " ORDER BY observed_at DESC LIMIT 120",
                (row, index) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", row.getLong("id"));
                    value.put("observedAt", row.getTimestamp("observed_at").toInstant());
                    value.put("httpStatus", row.getInt("http_status"));
                    value.put("reasonCode", row.getString("reason_code"));
                    value.put("recoverySource", row.getString("recovery_source"));
                    value.put("revision", row.getString("revision"));
                    value.put("snapshot", read(row.getString("snapshot_json")));
                    return value;
                },
                incident.targetCode(),
                incident.incidentId());
    }

    List<Map<String, Object>> changes(DemoTargetDtos.Incident incident) {
        Instant since = incident.startedAt().minusSeconds(900);
        Instant until =
                incident.recoveredAt() == null
                        ? Instant.now()
                        : incident.recoveredAt().plusSeconds(5);
        List<Map<String, Object>> result =
                new ArrayList<>(
                        jdbc.query(
                                "SELECT * FROM operations_demo_runtime_change WHERE target_code=?"
                                    + " AND occurred_at>=? AND occurred_at<=? ORDER BY occurred_at"
                                    + " DESC LIMIT 30",
                                (row, index) -> {
                                    Map<String, Object> value = new LinkedHashMap<>();
                                    value.put("id", "runtime-" + row.getLong("id"));
                                    value.put("targetCode", row.getString("target_code"));
                                    value.put("kind", row.getString("kind"));
                                    value.put("summary", "目标实际应用的运行配置；变更与故障的因果关系仍需业务证据验证");
                                    value.put(
                                            "occurredAt",
                                            row.getTimestamp("occurred_at").toInstant());
                                    value.put(
                                            "observedAt",
                                            row.getTimestamp("observed_at").toInstant());
                                    value.put("status", "OBSERVED");
                                    value.put("source", "TARGET_RUNTIME");
                                    value.put("before", read(row.getString("before_json")));
                                    value.put("after", read(row.getString("after_json")));
                                    value.put("causality", "NOT_ESTABLISHED");
                                    return value;
                                },
                                incident.targetCode(),
                                Timestamp.from(since),
                                Timestamp.from(until)));
        if (DemoTargetDtos.TARGET.equals(incident.targetCode())) {
            result.addAll(
                    jdbc.query(
                            "SELECT * FROM operations_managed_config_change WHERE"
                                + " configuration_id='order-business' AND action<>'BASELINE' AND"
                                + " created_at>=? AND created_at<=? ORDER BY created_at DESC LIMIT"
                                + " 30",
                            (row, index) -> {
                                Map<String, Object> value = new LinkedHashMap<>();
                                value.put("id", "nacos-" + row.getLong("id"));
                                value.put("targetCode", DemoTargetDtos.TARGET);
                                value.put("kind", "NACOS_CONFIGURATION_PUBLICATION");
                                value.put("summary", "订单业务配置发布记录；APPLIED表示后续读回确认，时间相近不等于导致故障");
                                value.put("occurredAt", row.getTimestamp("created_at").toInstant());
                                Timestamp finished = row.getTimestamp("finished_at");
                                value.put(
                                        "observedAt",
                                        finished == null ? null : finished.toInstant());
                                value.put("status", row.getString("status"));
                                value.put("source", "MANAGED_CONFIGURATION_HISTORY");
                                value.put("before", read(row.getString("previous_json")));
                                value.put("after", read(row.getString("content_json")));
                                value.put("causality", "NOT_ESTABLISHED");
                                return value;
                            },
                            Timestamp.from(since),
                            Timestamp.from(until)));
        }
        result.sort(Comparator.comparing(value -> (Instant) value.get("occurredAt")));
        return result;
    }

    ObjectNode safeSnapshot(JsonNode raw) {
        ObjectNode safe = json.createObjectNode();
        for (String key :
                List.of(
                        "targetCode",
                        "scope",
                        "incidentId",
                        "status",
                        "recoverySource",
                        "expectedRevision",
                        "expiresAt",
                        "appliedRevision",
                        "configurationStatus",
                        "configurationSource",
                        "configurationAppliedAt",
                        "configuration",
                        "redisPort",
                        "sentinel",
                        "consumerEnabled",
                        "queue",
                        "business",
                        "observedAt")) {
            if (raw.has(key)) safe.set(key, raw.get(key).deepCopy());
        }
        return safe;
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 120000)
    void trimObservations() {
        jdbc.update(
                "DELETE FROM operations_demo_observation WHERE observed_at<? LIMIT 3000",
                Timestamp.from(Instant.now().minusSeconds(14L * 86400)));
    }

    private JsonNode read(String content) {
        try {
            return json.readTree(content);
        } catch (Exception exception) {
            return json.createObjectNode().put("status", "STORED_EVIDENCE_UNREADABLE");
        }
    }
}
