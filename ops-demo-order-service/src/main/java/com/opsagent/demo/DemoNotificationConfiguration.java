package com.opsagent.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/**
 * 独立通知消费者的持久控制状态，故障不会改变公共 RabbitMQ 或任意队列。
 *
 * @author heyu
 * @since 2026/9/3
 */
record DemoNotificationConfiguration(
        String incidentId,
        String scenarioCode,
        String revision,
        long expiresAtEpoch,
        boolean consumerEnabled,
        String recoverySource) {
    static final String TARGET = "ops-demo-notification-service";
    static final String SCENARIO = "RABBITMQ_CONSUMER_PAUSED";

    static DemoNotificationConfiguration baseline(String incident, String source) {
        return create(incident, "", 0, true, source);
    }

    static DemoNotificationConfiguration fault(String incident, String scenario, Instant expiry) {
        if (!SCENARIO.equals(scenario)
                || !incident.matches("[a-f0-9-]{36}")
                || !expiry.isAfter(Instant.now())
                || expiry.isAfter(Instant.now().plusSeconds(905))) {
            throw new IllegalArgumentException("INVALID_SCENARIO");
        }
        return create(incident, scenario, expiry.getEpochSecond(), false, "");
    }

    boolean faulted() {
        return !consumerEnabled;
    }

    boolean valid() {
        return incidentId != null
                && incidentId.matches("(?:[a-f0-9-]{36})?")
                && scenarioCode != null
                && recoverySource != null
                && revision != null
                && (consumerEnabled
                        ? scenarioCode.isEmpty()
                                && expiresAtEpoch == 0
                                && Set.of("BASELINE", "MANUAL", "AGENT_TOOL", "TTL_GUARD")
                                        .contains(recoverySource)
                        : SCENARIO.equals(scenarioCode)
                                && incidentId.length() == 36
                                && expiresAtEpoch > 0
                                && expiresAtEpoch <= Instant.now().plusSeconds(905).getEpochSecond()
                                && recoverySource.isEmpty())
                && revision.equals(
                        create(
                                        incidentId,
                                        scenarioCode,
                                        expiresAtEpoch,
                                        consumerEnabled,
                                        recoverySource)
                                .revision());
    }

    private static DemoNotificationConfiguration create(
            String incident, String scenario, long expiry, boolean enabled, String source) {
        try {
            String content =
                    incident + "|" + scenario + "|" + expiry + "|" + enabled + "|" + source;
            String revision =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(content.getBytes(StandardCharsets.UTF_8)));
            return new DemoNotificationConfiguration(
                    incident, scenario, revision, expiry, enabled, source);
        } catch (Exception exception) {
            throw new IllegalStateException("HASH_UNAVAILABLE");
        }
    }
}
