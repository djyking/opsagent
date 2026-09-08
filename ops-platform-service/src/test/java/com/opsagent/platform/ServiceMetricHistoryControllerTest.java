package com.opsagent.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

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
