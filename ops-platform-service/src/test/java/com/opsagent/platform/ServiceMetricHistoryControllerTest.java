package com.opsagent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 历史曲线只接受独立、有效的原始采样，缺失和过期证据保持断档。
 *
 * @author heyu
 * @since 2026/9/3
 */
class ServiceMetricHistoryControllerTest {
    @Test
    void indexedMinuteRangeRetainsExactWindowAndEnvironmentBoundaries() throws Exception {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:service_history;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("DROP TABLE IF EXISTS obs_v3_topology_snapshot");
        jdbc.execute(
                "CREATE TABLE obs_v3_topology_snapshot(environment VARCHAR(32), observed_minute"
                    + " BIGINT, generated_at TIMESTAMP, payload_json CLOB, UNIQUE(environment,"
                    + " observed_minute))");
        var json = new ObjectMapper();
        var cmdb = mock(ItsmPlatformService.class);
        when(cmdb.ci("rag")).thenReturn(Map.of("environment", "PROD"));
        Instant now = Instant.now();
        Instant sampled = now.minusSeconds(60);
        var node =
                Map.of(
                        "ciCode",
                        "rag",
                        "environment",
                        "PROD",
                        "observation",
                        Map.of("fetchedAt", sampled.toString()),
                        "metricEvidence",
                        Map.of(
                                "rps",
                                Map.of("value", 0, "sampledAt", sampled.toString(), "unit", "/s")));
        String payload = json.writeValueAsString(Map.of("nodes", new Object[] {node}));
        for (String env : new String[] {"ALL", "PROD", "DEMO"}) {
            jdbc.update(
                    "INSERT INTO obs_v3_topology_snapshot VALUES(?,?,?,?)",
                    env,
                    sampled.getEpochSecond() / 60,
                    Timestamp.from(sampled),
                    payload);
        }
        Instant old = now.minusSeconds(3600);
        jdbc.update(
                "INSERT INTO obs_v3_topology_snapshot VALUES(?,?,?,?)",
                "PROD",
                old.getEpochSecond() / 60,
                Timestamp.from(old),
                payload);
        var result =
                new ServiceMetricHistoryController(jdbc, cmdb, json)
                        .history("rag", "PROD", "5m")
                        .data();
        var series = (Map<?, ?>) result.get("series");
        assertEquals(
                1,
                ((java.util.List<?>) series.get("rps")).size(),
                "Duplicates from ALL and PROD represent one original sample");
        assertEquals(
                2,
                ((Map<?, ?>) result.get("timing")).get("snapshotCount"),
                "Unrelated environments and old payloads are never read");
        assertThrows(
                com.opsagent.common.core.BusinessException.class,
                () ->
                        new ServiceMetricHistoryController(jdbc, cmdb, json)
                                .history("rag", "DEMO", "5m"));
    }

    @Test
    void keepsRealZeroAndHistoricalSamplesWithoutDuplicatingOrFillingMissingEvidence()
            throws Exception {
        var node =
                new ObjectMapper()
                        .readTree(
                                """
{"observation":{"fetchedAt":"2026-09-07T10:01:00Z","maximumSampleAgeSeconds":90},
 "metricEvidence":{
  "rps":{"value":0,"sampledAt":"2026-09-07T10:00:45Z","unit":"/s","scope":"REQUEST_WINDOW"},
  "p95Ms":{"value":null,"sampledAt":"2026-09-07T10:00:45Z"},
  "cpuUsage":{"value":33,"sampledAt":"2026-09-07T09:58:00Z"},
  "future":{"value":7,"sampledAt":"2026-09-07T10:02:00Z"}}}
""");
        Map<String, TreeMap<Instant, Map<String, Object>>> result = new LinkedHashMap<>();
        Instant start = Instant.parse("2026-09-07T09:55:00Z");
        Instant end = Instant.parse("2026-09-07T10:10:00Z");
        ServiceMetricHistoryController.collect(result, node, start, end);
        ServiceMetricHistoryController.collect(result, node, start, end);
        assertEquals(1, result.get("rps").size());
        assertEquals(0.0, result.get("rps").firstEntry().getValue().get("value"));
        assertFalse(result.containsKey("p95Ms"));
        assertFalse(result.containsKey("cpuUsage"));
        assertFalse(result.containsKey("future"));
    }
}
