package com.opsagent.platform;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 固定真实演练合同，不允许传递任意目标地址、配置键或命令。
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class DemoTargetDtos {
    public static final String TARGET = "ops-demo-order-service";
    public static final String NOTIFICATION_TARGET = "ops-demo-notification-service";
    public static final java.util.Set<String> TARGETS =
            java.util.Set.of(TARGET, NOTIFICATION_TARGET);

    static String scenarioTarget(String scenario) {
        return switch (scenario) {
            case "NACOS_REDIS_CONFIG_DRIFT", "SENTINEL_RULE_REGRESSION" -> TARGET;
            case "RABBITMQ_CONSUMER_PAUSED" -> NOTIFICATION_TARGET;
            default ->
                    throw new com.opsagent.common.core.BusinessException(
                            com.opsagent.common.core.ErrorCode.VALIDATION, "INVALID_DEMO_SCENARIO");
        };
    }

    static String targetForPath(String path) {
        return switch (path) {
            case "order" -> TARGET;
            case "notification" -> NOTIFICATION_TARGET;
            default ->
                    throw new com.opsagent.common.core.BusinessException(
                            com.opsagent.common.core.ErrorCode.NOT_FOUND,
                            "DEMO_TARGET_NOT_ALLOWED");
        };
    }

    static String recoveryAction(String scenario) {
        return switch (scenario) {
            case "NACOS_REDIS_CONFIG_DRIFT" -> "RESTORE_CONFIGURATION";
            case "SENTINEL_RULE_REGRESSION" -> "RESTORE_FLOW_RULE";
            case "RABBITMQ_CONSUMER_PAUSED" -> "RESTORE_QUEUE_CONSUMER";
            default ->
                    throw new com.opsagent.common.core.BusinessException(
                            com.opsagent.common.core.ErrorCode.VALIDATION, "INVALID_DEMO_SCENARIO");
        };
    }

    private DemoTargetDtos() {}

    /**
     * @author heyu
     */
    public record CreateScenario(
            @NotBlank
                    @Pattern(
                            regexp =
                                    "NACOS_REDIS_CONFIG_DRIFT|SENTINEL_RULE_REGRESSION|RABBITMQ_CONSUMER_PAUSED")
                    String scenarioCode,
            @Min(180) @Max(900) Integer ttlSeconds) {}

    /**
     * @author heyu
     */
    public record Action(
            @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String incidentId,
            @NotBlank
                    @Pattern(
                            regexp =
                                    "RESTORE_CONFIGURATION|RESTORE_FLOW_RULE|RESTORE_QUEUE_CONSUMER")
                    String action,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedRevision,
            @NotBlank @Size(max = 160) String idempotencyKey) {}

    /**
     * @author heyu
     */
    public record Incident(
            String incidentId,
            String targetCode,
            String scenarioCode,
            long ownerId,
            String ownerName,
            String ownerKind,
            String status,
            String expectedRevision,
            Instant startedAt,
            Instant expiresAt,
            Instant recoveredAt,
            String recoverySource,
            int lastHttpStatus,
            String lastReason,
            Instant lastObservedAt) {}

    /**
     * @author heyu
     */
    public record IncidentOwner(
            String incidentId,
            long ownerId,
            String ownerName,
            String ownerKind,
            String scenarioCode,
            Instant startedAt,
            Instant expiresAt) {}
}
