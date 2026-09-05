package com.opsagent.platform;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/**
 * 查询 CMDB Lite 服务目录、依赖拓扑和值班排班数据。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
public class ItsmPlatformRepository {
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    ItsmPlatformRepository(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    List<Map<String, Object>> cis(String keyword, String type) {
        String query = keyword == null ? "" : keyword.trim();
        String ciType = type == null ? "" : type.trim();
        return jdbc.queryForList(
                """
                SELECT id,ci_code ciCode,ci_name ciName,ci_type ciType,environment,
                       owner_name ownerName,endpoint,status,description,update_time updateTime
                FROM cmdb_ci
                WHERE (?='' OR ci_code LIKE CONCAT('%',?,'%') OR ci_name LIKE CONCAT('%',?,'%'))
                  AND (?='' OR ci_type=?)
                ORDER BY ci_type,ci_code
                """,
                query,
                query,
                query,
                ciType,
                ciType);
    }

    Map<String, Object> ci(String ciCode) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT id,ci_code ciCode,ci_name ciName,ci_type ciType,environment,
                       owner_name ownerName,endpoint,status,description,update_time updateTime
                FROM cmdb_ci WHERE ci_code=?
                """,
                ciCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    Map<String, Object> ci(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT id,ci_code ciCode,ci_name ciName,ci_type ciType,environment,
                       owner_name ownerName,endpoint,status,description,update_time updateTime
                FROM cmdb_ci WHERE id=?
                """,
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    long addCi(
            String ciCode,
            String ciName,
            String ciType,
            String environment,
            String ownerName,
            String endpoint,
            String status,
            String description) {
        jdbc.update(
                """
                INSERT INTO cmdb_ci(ci_code,ci_name,ci_type,environment,owner_name,endpoint,
                    status,description,create_time,update_time)
                VALUES(?,?,?,?,?,?,?,?,NOW(),NOW())
                """,
                ciCode,
                ciName,
                ciType,
                environment,
                ownerName,
                endpoint,
                status,
                description);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    int updateCi(
            long id,
            String ciCode,
            String ciName,
            String ciType,
            String environment,
            String ownerName,
            String endpoint,
            String status,
            String description) {
        return jdbc.update(
                """
                UPDATE cmdb_ci SET ci_code=?,ci_name=?,ci_type=?,environment=?,owner_name=?,
                    endpoint=?,status=?,description=?,update_time=NOW() WHERE id=?
                """,
                ciCode,
                ciName,
                ciType,
                environment,
                ownerName,
                endpoint,
                status,
                description,
                id);
    }

    List<Map<String, Object>> relations() {
        return jdbc.queryForList(
                """
                SELECT id,source_ci_code sourceCiCode,target_ci_code targetCiCode,
                       relation_type relationType,description,create_time createTime
                FROM cmdb_relation ORDER BY source_ci_code,target_ci_code
                """);
    }

    long addRelation(
            String sourceCiCode,
            String targetCiCode,
            String relationType,
            String description) {
        jdbc.update(
                """
                INSERT INTO cmdb_relation(source_ci_code,target_ci_code,relation_type,
                    description,create_time) VALUES(?,?,?,?,NOW())
                """,
                sourceCiCode,
                targetCiCode,
                relationType,
                description);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    int deleteRelation(long id) {
        return jdbc.update("DELETE FROM cmdb_relation WHERE id=?", id);
    }

    Map<String, Object> topology(String ciCode) {
        Map<String, Object> root = ci(ciCode);
        if (root == null) {
            return null;
        }
        List<Map<String, Object>> edges = jdbc.queryForList(
                """
                SELECT source_ci_code sourceCiCode,target_ci_code targetCiCode,
                       relation_type relationType,description
                FROM cmdb_relation
                WHERE source_ci_code=? OR target_ci_code=?
                ORDER BY id
                """,
                ciCode,
                ciCode);
        List<String> codes = edges.stream()
                .flatMap(row -> List.of(
                                String.valueOf(row.get("sourceCiCode")),
                                String.valueOf(row.get("targetCiCode")))
                        .stream())
                .distinct()
                .toList();
        List<Map<String, Object>> nodes = codes.stream()
                .map(this::ci)
                .filter(java.util.Objects::nonNull)
                .toList();
        return Map.of("root", root, "nodes", nodes, "edges", edges);
    }

    List<Map<String, Object>> schedules() {
        return jdbc.queryForList(
                """
                SELECT id,schedule_code scheduleCode,schedule_name scheduleName,
                       service_ci_code serviceCiCode,timezone,enabled,update_time updateTime
                FROM oncall_schedule ORDER BY id
                """);
    }

    Map<String, Object> schedule(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT id,schedule_code scheduleCode,schedule_name scheduleName,
                       service_ci_code serviceCiCode,timezone,enabled,update_time updateTime
                FROM oncall_schedule WHERE id=?
                """,
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    long addSchedule(
            String code, String name, String serviceCiCode, String timezone, boolean enabled) {
        jdbc.update(
                """
                INSERT INTO oncall_schedule(schedule_code,schedule_name,service_ci_code,timezone,
                    enabled,create_time,update_time) VALUES(?,?,?,?,?,NOW(),NOW())
                """,
                code,
                name,
                serviceCiCode,
                timezone,
                enabled);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    int updateSchedule(
            long id,
            String code,
            String name,
            String serviceCiCode,
            String timezone,
            boolean enabled) {
        return jdbc.update(
                """
                UPDATE oncall_schedule SET schedule_code=?,schedule_name=?,service_ci_code=?,
                    timezone=?,enabled=?,update_time=NOW() WHERE id=?
                """,
                code,
                name,
                serviceCiCode,
                timezone,
                enabled,
                id);
    }

    List<Map<String, Object>> shifts(Long scheduleId) {
        if (scheduleId == null) {
            return jdbc.queryForList(
                    """
                    SELECT sh.id,sh.schedule_id scheduleId,s.schedule_name scheduleName,
                           sh.role_type roleType,sh.user_id userId,sh.user_name userName,
                           sh.start_time startTime,sh.end_time endTime
                    FROM oncall_shift sh JOIN oncall_schedule s ON s.id=sh.schedule_id
                    WHERE sh.end_time>=NOW() ORDER BY sh.start_time,sh.role_type LIMIT 100
                    """);
        }
        return jdbc.queryForList(
                """
                SELECT sh.id,sh.schedule_id scheduleId,s.schedule_name scheduleName,
                       sh.role_type roleType,sh.user_id userId,sh.user_name userName,
                       sh.start_time startTime,sh.end_time endTime
                FROM oncall_shift sh JOIN oncall_schedule s ON s.id=sh.schedule_id
                WHERE sh.schedule_id=? AND sh.end_time>=NOW()
                ORDER BY sh.start_time,sh.role_type LIMIT 100
                """,
                scheduleId);
    }

    Map<String, Object> shift(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT sh.id,sh.schedule_id scheduleId,s.schedule_name scheduleName,
                       sh.role_type roleType,sh.user_id userId,sh.user_name userName,
                       sh.start_time startTime,sh.end_time endTime
                FROM oncall_shift sh JOIN oncall_schedule s ON s.id=sh.schedule_id
                WHERE sh.id=?
                """,
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    long addShift(
            long scheduleId,
            String roleType,
            long userId,
            String userName,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        jdbc.update(
                """
                INSERT INTO oncall_shift(schedule_id,role_type,user_id,user_name,start_time,
                    end_time,create_time) VALUES(?,?,?,?,?,?,NOW())
                """,
                scheduleId,
                roleType,
                userId,
                userName,
                startTime,
                endTime);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    int updateShift(
            long id,
            long scheduleId,
            String roleType,
            long userId,
            String userName,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        return jdbc.update(
                """
                UPDATE oncall_shift SET schedule_id=?,role_type=?,user_id=?,user_name=?,
                    start_time=?,end_time=? WHERE id=?
                """,
                scheduleId,
                roleType,
                userId,
                userName,
                startTime,
                endTime,
                id);
    }

    int deleteShift(long id) {
        return jdbc.update("DELETE FROM oncall_shift WHERE id=?", id);
    }

    CurrentOnCallResponse currentOnCall(String serviceCiCode) {
        metrics.counter("opsagent.oncall.lookup").increment();
        String service = serviceCiCode == null ? "" : serviceCiCode.trim();
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT s.schedule_code scheduleCode,s.schedule_name scheduleName,
                       s.service_ci_code serviceCiCode,sh.role_type roleType,
                       sh.user_id userId,sh.user_name userName,
                       sh.start_time startTime,sh.end_time endTime
                FROM oncall_schedule s JOIN oncall_shift sh ON sh.schedule_id=s.id
                WHERE s.enabled=1 AND sh.start_time<=NOW() AND sh.end_time>NOW()
                  AND (?='' OR s.service_ci_code=? OR s.service_ci_code IS NULL)
                ORDER BY FIELD(sh.role_type,'PRIMARY','SECONDARY'),s.id
                """,
                service,
                service);
        if (rows.isEmpty()) {
            metrics.counter("opsagent.oncall.miss").increment();
            return new CurrentOnCallResponse(true, "当前无有效排班，请联系系统管理员", List.of());
        }
        return new CurrentOnCallResponse(false, "", rows);
    }
}
