package com.opsagent.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/**
 * Nacos 专用配置只允许两个故障预设；端点、键和脚本不属于可配置参数。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record DemoConfiguration(
        String incidentId,
        String scenarioCode,
        String revision,
        long expiresAtEpoch,
        int redisPort,
        int qps,
        String recoverySource) {
    public static final String TARGET = "ops-demo-order-service";
    public static final String RESOURCE = "ops-demo-order-query";
    public static final Set<String> SCENARIOS =
            Set.of("NACOS_REDIS_CONFIG_DRIFT", "SENTINEL_RULE_REGRESSION");

    public static DemoConfiguration baseline(String incident, String source) {
        return create(incident, "", 0, 6379, 5, source);
    }

    public static DemoConfiguration fault(String incident, String scenario, Instant expiresAt) {
        if (!SCENARIOS.contains(scenario)
                || !incident.matches("[a-f0-9-]{36}")
                || expiresAt.isAfter(Instant.now().plusSeconds(905))
                || !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("INVALID_SCENARIO");
        }
        return create(
                incident,
                scenario,
                expiresAt.getEpochSecond(),
                scenario.equals("NACOS_REDIS_CONFIG_DRIFT") ? 6380 : 6379,
                scenario.equals("SENTINEL_RULE_REGRESSION") ? 0 : 5,
                "");
    }

    public boolean faulted() {
        return redisPort != 6379 || qps != 5;
    }

    public boolean valid() {
        boolean preset =
                !faulted()
                        ? scenarioCode != null && scenarioCode.isEmpty() && expiresAtEpoch == 0
                        : "NACOS_REDIS_CONFIG_DRIFT".equals(scenarioCode)
                                        && redisPort == 6380
                                        && qps == 5
                                || "SENTINEL_RULE_REGRESSION".equals(scenarioCode)
                                        && redisPort == 6379
                                        && qps == 0;
        return preset
                && (!faulted()
                        || expiresAtEpoch > 0
                                && expiresAtEpoch
                                        <= Instant.now().plusSeconds(905).getEpochSecond())
                && incidentId != null
                && incidentId.matches("(?:[a-f0-9-]{36})?")
                && (redisPort == 6379 || redisPort == 6380)
                && (qps == 0 || qps == 5)
                && scenarioCode != null
                && (scenarioCode.isEmpty() || SCENARIOS.contains(scenarioCode))
                && recoverySource != null
                && Set.of("", "BASELINE", "AGENT_TOOL", "MANUAL", "TTL_GUARD")
                        .contains(recoverySource)
                && revision != null
                && revision.equals(
                        create(
                                        incidentId,
                                        scenarioCode,
                                        expiresAtEpoch,
                                        redisPort,
                                        qps,
                                        recoverySource)
                                .revision());
    }

    private static DemoConfiguration create(
            String incident, String scenario, long expires, int port, int qps, String source) {
        String content =
                incident + "|" + scenario + "|" + expires + "|" + port + "|" + qps + "|" + source;
        try {
            String revision =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(content.getBytes(StandardCharsets.UTF_8)));
            return new DemoConfiguration(incident, scenario, revision, expires, port, qps, source);
        } catch (Exception exception) {
            throw new IllegalStateException("HASH_UNAVAILABLE", exception);
        }
    }
}
