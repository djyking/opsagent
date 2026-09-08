package com.opsagent.platform;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Safe projections of configuration provenance and masked revisions.
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class ConfigCenterDtos {
    private ConfigCenterDtos() {}

    public record Source(String source, int count, String status) {}

    public record Identity(
            String sourceType,
            String sourceInstanceId,
            String environment,
            String namespaceId,
            String group,
            String dataId,
            List<String> targetScope) {}

    public record Capabilities(
            boolean canRead,
            boolean canDiff,
            boolean canEdit,
            boolean canPublish,
            boolean canRollback,
            boolean canVerifyApplied,
            Map<String, String> reasons) {}

    public record Summary(
            String status,
            Integer configurationCount,
            Integer editableCount,
            List<Source> sources,
            String message,
            Instant observedAt) {}

    public record Item(
            String id,
            String name,
            String serviceId,
            String source,
            String namespace,
            String group,
            String dataId,
            String format,
            boolean editable,
            String managementPath,
            String status,
            String description,
            String modifiedAt,
            Identity identity,
            Capabilities capabilities,
            boolean shared) {}

    public record Catalog(String status, List<Item> items, String message, Instant observedAt) {}

    public record Detail(
            Item item,
            String status,
            String content,
            String revision,
            String message,
            Instant observedAt,
            Overview overview) {}

    public record Overview(
            String status,
            String serviceId,
            String instanceId,
            Instant observedAt,
            String message,
            List<com.opsagent.common.security.RuntimeConfigurationSnapshot.Field> fields) {}

    public record Version(long id, String actor, String modifiedAt, String operation) {}

    public record History(String status, List<Version> items, String message) {}

    public record Diff(
            String status,
            long versionId,
            String currentContent,
            String previousContent,
            String message) {}
}
