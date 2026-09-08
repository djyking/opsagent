package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 服务侧栏只读趋势，来源为已有采集快照；不插值、不使用当前值补造历史。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class ServiceMetricHistoryController {
    private static final Map<String, Long> WINDOWS =
            Map.of("5m", 300L, "15m", 900L, "30m", 1800L, "1h", 3600L, "6h", 21600L);
    private final JdbcTemplate jdbc;
    private final ItsmPlatformService cmdb;
    private final ObjectMapper json;

    ServiceMetricHistoryController(JdbcTemplate jdbc, ItsmPlatformService cmdb, ObjectMapper json) {
        this.jdbc = jdbc;
        this.cmdb = cmdb;
        this.json = json;
    }

    @GetMapping("/api/platform/observability/services/{ciCode}/metric-history")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Map<String, Object>> history(
            @PathVariable String ciCode,
            @RequestParam(defaultValue = "ALL") String environment,
            @RequestParam(defaultValue = "15m") String timeRange) {
        Long seconds = WINDOWS.get(timeRange);
        if (seconds == null) throw new BusinessException(ErrorCode.VALIDATION, "不支持的趋势时间窗口");
        Map<String, Object> ci = cmdb.ci(ciCode);
        String registeredEnvironment = String.valueOf(ci.get("environment"));
        if (!"ALL".equals(environment) && !registeredEnvironment.equals(environment))
            throw new BusinessException(ErrorCode.VALIDATION, "所选环境与服务登记不一致");
        Instant end = Instant.now();
        Instant start = end.minusSeconds(seconds);
        var rows =
                jdbc.queryForList(
                        "SELECT payload_json FROM obs_v3_topology_snapshot WHERE environment IN (?,"
                            + " 'ALL') AND generated_at>=? AND generated_at<=? ORDER BY"
                            + " generated_at DESC LIMIT 750",
                        String.class,
                        registeredEnvironment,
                        Timestamp.from(start),
                        Timestamp.from(end));
        Map<String, TreeMap<Instant, Map<String, Object>>> metrics = new LinkedHashMap<>();
        for (String row : rows) {
            try {
                JsonNode snapshot = json.readTree(row);
                for (JsonNode node : snapshot.path("nodes")) {
                    if (!ciCode.equals(node.path("ciCode").asText())
                            || !registeredEnvironment.equals(node.path("environment").asText()))
                        continue;
                    collect(metrics, node, start, end);
                }
            } catch (Exception ignored) {
                // Invalid historical rows do not establish a measurement.
            }
        }
        Map<String, Object> series = new LinkedHashMap<>();
        metrics.forEach((key, points) -> series.put(key, new ArrayList<>(points.values())));
        return ApiResponse.success(
                Map.of(
                        "ciCode",
                        ciCode,
                        "environment",
                        registeredEnvironment,
                        "from",
                        start,
                        "to",
                        end,
                        "source",
                        "OBSERVATION_SNAPSHOT",
                        "series",
                        series,
                        "checkedAt",
                        end,
                        "message",
                        "真实采集快照，按原始样本时间展示；断档不补零，至少两个不同样本才显示趋势"));
    }

    static void collect(
            Map<String, TreeMap<Instant, Map<String, Object>>> result,
            JsonNode node,
            Instant start,
            Instant end) {
        long maxAge =
                Math.max(1, node.path("observation").path("maximumSampleAgeSeconds").asLong(90));
        JsonNode evidence = node.path("metricEvidence");
        var names = evidence.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode metric = evidence.path(name);
            if (!metric.path("value").isNumber()
                    || !Double.isFinite(metric.path("value").asDouble())) continue;
            try {
                Instant at = Instant.parse(metric.path("sampledAt").asText());
                Instant observed =
                        Instant.parse(node.path("observation").path("fetchedAt").asText());
                if (at.isBefore(start)
                        || at.isAfter(end)
                        || at.isAfter(observed.plusSeconds(5))
                        || at.isBefore(observed.minusSeconds(maxAge))) continue;
                result.computeIfAbsent(name, ignored -> new TreeMap<>())
                        .putIfAbsent(
                                at,
                                Map.of(
                                        "at",
                                        at.toString(),
                                        "value",
                                        metric.path("value").asDouble(),
                                        "unit",
                                        metric.path("unit").asText(),
                                        "scope",
                                        metric.path("scope").asText()));
            } catch (RuntimeException ignored) {
                // Preserve a gap when timestamps are unavailable or invalid.
            }
        }
    }
}
