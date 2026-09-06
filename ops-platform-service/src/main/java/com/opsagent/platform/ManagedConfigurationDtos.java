package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 配置中心只展示与编辑纳管的无密钥配置，不返回Nacos凭据。
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class ManagedConfigurationDtos {
    private ManagedConfigurationDtos() {}

    /**
     * @author heyu
     */
    public record Definition(
            String id,
            String name,
            String group,
            String dataId,
            boolean editable,
            String description) {}

    /**
     * @author heyu
     */
    public record Detail(
            String id,
            String name,
            String group,
            String dataId,
            boolean editable,
            String description,
            JsonNode content,
            String revision,
            String appliedRevision,
            String applicationStatus,
            String nacosStatus,
            boolean canPublish,
            String blockedReason,
            Instant observedAt,
            JsonNode business) {}

    /**
     * @author heyu
     */
    public record Validate(@NotNull JsonNode content) {}

    /**
     * @author heyu
     */
    public record Validated(boolean valid, JsonNode normalizedContent, String description) {}

    /**
     * @author heyu
     */
    public record Publish(
            @NotNull JsonNode content,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedRevision,
            @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String requestId,
            @NotBlank @Size(max = 500) String comment) {}

    /**
     * @author heyu
     */
    public record Rollback(
            @Min(1) long versionId,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedRevision,
            @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String requestId,
            @NotBlank @Size(max = 500) String comment) {}

    /**
     * @author heyu
     */
    public record History(
            long id,
            long version,
            String action,
            String status,
            JsonNode content,
            JsonNode previousContent,
            String revision,
            String expectedRevision,
            String comment,
            String actorName,
            Instant createdAt,
            Instant finishedAt,
            Long rollbackVersionId,
            String message) {}

    /**
     * @author heyu
     */
    public record HistoryPage(List<History> items, long total, int page, int size) {}

    /**
     * @author heyu
     */
    public record Result(History operation, Detail configuration) {}
}
