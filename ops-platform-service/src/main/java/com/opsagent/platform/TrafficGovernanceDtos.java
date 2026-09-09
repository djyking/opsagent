package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

/**
 * Sentinel traffic metrics and governed rule publication contracts.
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class TrafficGovernanceDtos {
    private TrafficGovernanceDtos() {}

    public record Summary(
            String status,
            String serviceId,
            Integer resourceCount,
            Integer ruleCount,
            Double passQps,
            Double blockQps,
            Double avgRt,
            Integer activeThreads,
            String message,
            Instant observedAt) {}

    public record Resource(
            String resource,
            String label,
            String status,
            Double passQps,
            Double blockQps,
            Double avgRt,
            Integer activeThreads,
            String measurement) {}

    public record RuleSet(
            String type,
            String label,
            String status,
            boolean supported,
            boolean editable,
            String dataId,
            String revision,
            JsonNode persistedRules,
            JsonNode appliedRules,
            String applicationStatus,
            String message) {}

    public record Workspace(Summary summary, List<Resource> resources, List<RuleSet> ruleSets) {}

    public record Overview(
            String source, int windowSeconds, Instant refreshedAt, List<Stream> streams) {}

    public record Stream(
            String id,
            String label,
            String serviceId,
            String status,
            Double requestsPerSecond,
            Double requestCount,
            Double blockedCount,
            Double apiRequestsPerSecond,
            Instant sampledAt,
            String scope,
            String message) {}

    public record Validate(@NotNull JsonNode rules) {}

    public record Validated(boolean valid, JsonNode rules, String message) {}

    public record Publish(
            @NotNull JsonNode rules,
            @Pattern(regexp = "[a-f0-9]{64}") @NotBlank String expectedRevision,
            @Pattern(regexp = "[a-fA-F0-9-]{36}") @NotBlank String requestId,
            @NotBlank @Size(max = 500) String comment) {}

    public record Rollback(
            @Min(1) long versionId,
            @Pattern(regexp = "[a-f0-9]{64}") @NotBlank String expectedRevision,
            @Pattern(regexp = "[a-fA-F0-9-]{36}") @NotBlank String requestId,
            @NotBlank @Size(max = 500) String comment) {}

    public record Change(
            long id,
            String type,
            String action,
            String status,
            JsonNode before,
            JsonNode after,
            String expectedRevision,
            String revision,
            String comment,
            String actor,
            Instant createdAt,
            Instant finishedAt,
            Long rollbackVersionId,
            String message) {}

    public record History(List<Change> items) {}

    public record Result(Change operation, RuleSet ruleSet) {}
}
