package com.opsagent.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import jakarta.annotation.PostConstruct;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 仅存拓扑摘要、真实实例生命期、人工差异处理及独立事件证据；不复制原始Trace。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
class ObservabilityV3Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ObservabilityV3Repository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @PostConstruct
    void initialize() {
        var source = jdbc.getDataSource();
        if (source != null)
            new ResourceDatabasePopulator(new ClassPathResource("observability-v3-schema.sql"))
                    .execute(source);
    }

    void snapshot(Map<String, Object> topology) {
        Instant generated = Instant.now();
        String env = String.valueOf(topology.get("environment"));
        String quality = String.valueOf(topology.getOrDefault("dataQuality", "PARTIAL"));
        String payload = write(topology);
        if (payload.length() > 1_000_000) throw new IllegalStateException("拓扑快照超出大小上限");
        try {
            jdbc.update(
                    "INSERT INTO"
                        + " obs_v3_topology_snapshot(id,environment,observed_minute,window_start,"
                        + "window_end,generated_at,graph_version,data_quality,payload_json)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),
                    env,
                    generated.getEpochSecond() / 60,
                    time(topology.get("windowStart")),
                    time(topology.get("windowEnd")),
                    Timestamp.from(generated),
                    topology.get("graphVersion"),
                    quality,
                    payload);
        } catch (DuplicateKeyException sameMinute) {
            // Preserve the first committed immutable observation of this scope/minute.
        }
    }

    Map<String, Object> history(String env, Instant from, Instant to) {
        List<Map<String, Object>> rows =
                jdbc.query(
                        "SELECT"
                            + " id,environment,window_start,window_end,generated_at,graph_version,data_quality"
                            + " FROM obs_v3_topology_snapshot WHERE environment=? AND"
                            + " generated_at>=? AND generated_at<=? ORDER BY generated_at DESC"
                            + " LIMIT 200",
                        (rs, index) ->
                                Map.of(
                                        "id",
                                        rs.getString(1),
                                        "environment",
                                        rs.getString(2),
                                        "windowStart",
                                        rs.getTimestamp(3).toInstant().toString(),
                                        "windowEnd",
                                        rs.getTimestamp(4).toInstant().toString(),
                                        "generatedAt",
                                        rs.getTimestamp(5).toInstant().toString(),
                                        "graphVersion",
                                        rs.getString(6),
                                        "dataQuality",
                                        rs.getString(7)),
                        env,
                        Timestamp.from(from),
                        Timestamp.from(to));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put(
                "oldestAt",
                jdbc.queryForObject(
                        "SELECT MIN(generated_at) FROM obs_v3_topology_snapshot WHERE"
                                + " environment=?",
                        Timestamp.class,
                        env));
        result.put("retentionHours", 72);
        result.put("message", rows.isEmpty() ? "该时间范围无历史数据；未使用当前拓扑补造过去" : "仅返回实际采集的离散快照，缺失分钟表示断档");
        return result;
    }

    Map<String, Object> snapshot(String id) {
        List<String> rows =
                jdbc.queryForList(
                        "SELECT payload_json FROM obs_v3_topology_snapshot WHERE id=?",
                        String.class,
                        id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "历史快照不存在或已超过留存期");
        Map<String, Object> result = read(rows.get(0));
        result.put("history", true);
        result.put("snapshotId", id);
        return result;
    }

    void recordSpans(List<Map<String, Object>> spans) {
        Instant now = Instant.now();
        Map<List<String>, InstanceObservation> observations = new LinkedHashMap<>();
        for (Map<String, Object> span : spans.stream().limit(500).toList()) {
            String instance = String.valueOf(span.getOrDefault("instanceId", ""));
            if (instance.isBlank() || instance.length() > 150) continue;
            Instant seen;
            try {
                seen = Instant.parse(String.valueOf(span.get("startTime")));
            } catch (RuntimeException malformed) {
                continue;
            }
            if (seen.isBefore(now.minusSeconds(7 * 86400)) || seen.isAfter(now.plusSeconds(30)))
                continue;
            String env = String.valueOf(span.get("environment"));
            String ci = String.valueOf(span.get("ciCode"));
            if (!Set.of("PROD", "DEMO", "DEV", "TEST", "STAGING").contains(env)
                    || !ci.matches("[a-zA-Z0-9_-]{1,64}")) continue;
            var key = List.of(env, ci, instance);
            var previous = observations.get(key);
            observations.put(
                    key,
                    new InstanceObservation(
                            ci,
                            env,
                            instance,
                            previous == null || seen.isBefore(previous.first())
                                    ? seen
                                    : previous.first(),
                            previous == null || seen.isAfter(previous.last())
                                    ? seen
                                    : previous.last(),
                            previous == null || !seen.isBefore(previous.last())
                                    ? span.getOrDefault("metadata", Map.of())
                                    : previous.metadata()));
        }
        for (var observed : observations.values()) {
            if (updateInstance(observed) == 0)
                try {
                    jdbc.update(
                            "INSERT INTO"
                                    + " obs_v3_runtime_instance(environment,ci_code,instance_id,"
                                    + "first_seen_at,last_seen_at,metadata_json)"
                                    + " VALUES(?,?,?,?,?,?)",
                            observed.env(),
                            observed.ci(),
                            observed.instance(),
                            Timestamp.from(observed.first()),
                            Timestamp.from(observed.last()),
                            write(observed.metadata()));
                } catch (DuplicateKeyException concurrent) {
                    updateInstance(observed);
                }
        }
    }

    private int updateInstance(InstanceObservation observed) {
        // Update metadata before last_seen_at so MySQL evaluates against the previous timestamp.
        return jdbc.update(
                "UPDATE obs_v3_runtime_instance SET metadata_json=CASE WHEN last_seen_at<=? THEN ?"
                    + " ELSE metadata_json END,first_seen_at=CASE WHEN first_seen_at>? THEN ? ELSE"
                    + " first_seen_at END,last_seen_at=CASE WHEN last_seen_at<? THEN ? ELSE"
                    + " last_seen_at END WHERE environment=? AND ci_code=? AND instance_id=?",
                Timestamp.from(observed.last()),
                write(observed.metadata()),
                Timestamp.from(observed.first()),
                Timestamp.from(observed.first()),
                Timestamp.from(observed.last()),
                Timestamp.from(observed.last()),
                observed.env(),
                observed.ci(),
                observed.instance());
    }

    /**
     * @author heyu
     */
    private record InstanceObservation(
            String ci, String env, String instance, Instant first, Instant last, Object metadata) {}

    List<Map<String, Object>> instances(String ci, String env, Instant since) {
        return jdbc.query(
                "SELECT instance_id,first_seen_at,last_seen_at,metadata_json FROM"
                        + " obs_v3_runtime_instance WHERE ci_code=? AND environment=? AND"
                        + " last_seen_at>=? ORDER BY last_seen_at DESC LIMIT 50",
                (rs, index) -> {
                    Instant last = rs.getTimestamp(3).toInstant();
                    Map<String, Object> metadata = read(rs.getString(4));
                    return Map.<String, Object>of(
                            "instanceId",
                            rs.getString(1),
                            "ciCode",
                            ci,
                            "environment",
                            env,
                            "runtimeKind",
                            metadata.getOrDefault("runtimeKind", "JVM"),
                            "firstSeenAt",
                            rs.getTimestamp(2).toInstant().toString(),
                            "lastSeenAt",
                            last.toString(),
                            "observationStatus",
                            last.isAfter(Instant.now().minusSeconds(180))
                                    ? "RECENTLY_OBSERVED"
                                    : "NOT_RECENTLY_OBSERVED",
                            "source",
                            "TRACE_RESOURCE",
                            "metadata",
                            metadata);
                },
                ci,
                env,
                Timestamp.from(since));
    }

    Map<String, Object> handling(String id) {
        return handling(List.of(id)).getOrDefault(id, Map.of("decision", "OPEN", "note", ""));
    }

    Map<String, Map<String, Object>> handling(List<String> ids) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        List<String> unique = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
        for (int offset = 0; offset < unique.size(); offset += 200) {
            List<String> page = unique.subList(offset, Math.min(offset + 200, unique.size()));
            jdbc.query(
                    "SELECT id,decision,note,expires_at,actor_id,updated_at FROM"
                            + " obs_v3_difference_decision WHERE id IN ("
                            + String.join(",", Collections.nCopies(page.size(), "?"))
                            + ")",
                    (rs, index) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        var expiry = rs.getTimestamp(4);
                        boolean expired =
                                "IGNORE".equals(rs.getString(2))
                                        && expiry != null
                                        && !expiry.toInstant().isAfter(Instant.now());
                        row.put("decision", expired ? "OPEN" : rs.getString(2));
                        row.put("note", rs.getString(3));
                        row.put("expired", expired);
                        row.put("expiresAt", expiry == null ? null : expiry.toInstant().toString());
                        row.put("actorId", rs.getLong(5));
                        row.put("updatedAt", rs.getTimestamp(6).toInstant().toString());
                        result.put(rs.getString(1), row);
                        return row;
                    },
                    page.toArray());
        }
        return result;
    }

    @Transactional
    void decision(String id, String env, String decision, String note, Instant expiry, long actor) {
        int count =
                jdbc.update(
                        "UPDATE obs_v3_difference_decision SET"
                            + " decision=?,note=?,expires_at=?,actor_id=?,updated_at=? WHERE id=?",
                        decision,
                        note,
                        expiry == null ? null : Timestamp.from(expiry),
                        actor,
                        Timestamp.from(Instant.now()),
                        id);
        if (count == 0)
            jdbc.update(
                    "INSERT INTO"
                        + " obs_v3_difference_decision(id,environment,decision,note,expires_at,actor_id,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?)",
                    id,
                    env,
                    decision,
                    note,
                    expiry == null ? null : Timestamp.from(expiry),
                    actor,
                    Timestamp.from(Instant.now()));
    }

    String evidence(long actor, String ci, String env, Long ticket, Map<String, Object> bundle) {
        String id = UUID.randomUUID().toString();
        bundle.put("evidenceBundleId", id);
        jdbc.update(
                "INSERT INTO"
                    + " obs_v3_evidence_bundle(id,actor_id,ci_code,environment,ticket_id,created_at,payload_json)"
                    + " VALUES(?,?,?,?,?,?,?)",
                id,
                actor,
                ci,
                env,
                ticket,
                Timestamp.from(Instant.now()),
                write(bundle));
        return id;
    }

    Map<String, Object> evidence(String id, long actor, boolean admin) {
        var rows =
                jdbc.queryForList(
                        "SELECT payload_json FROM obs_v3_evidence_bundle WHERE id=? AND (actor_id=?"
                                + " OR ?=1)",
                        String.class,
                        id,
                        actor,
                        admin ? 1 : 0);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "证据包不存在或不属于当前权限范围");
        return read(rows.get(0));
    }

    void cleanup(Instant cutoff) {
        jdbc.update(
                "DELETE FROM obs_v3_topology_snapshot WHERE generated_at<?",
                Timestamp.from(cutoff));
        // Evidence bundles and approvals are deliberately not part of short-lived topology
        // retention.
    }

    private Timestamp time(Object value) {
        return Timestamp.from(Instant.parse(String.valueOf(value)));
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("观测摘要序列化失败", failure);
        }
    }

    Map<String, Object> read(String value) {
        try {
            return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception failure) {
            throw new IllegalStateException("观测摘要读取失败", failure);
        }
    }
}
