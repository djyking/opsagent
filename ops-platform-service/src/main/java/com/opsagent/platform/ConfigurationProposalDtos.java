package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Immutable configuration intent; approvals remain owned by the Agent service.
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class ConfigurationProposalDtos {
    private ConfigurationProposalDtos() {}

    public record Patch(@NotBlank String op, @NotBlank String path, JsonNode value) {}

    public record Create(
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedRevision,
            @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String requestId,
            @NotBlank @Size(max = 500) String comment,
            @Size(max = 3) List<@Valid Patch> patch,
            Long rollbackVersionId) {}

    public record Apply(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String immutableDigest) {}

    public record Change(String field, JsonNode before, JsonNode after) {}

    public record Proposal(
            String proposalId,
            String immutableDigest,
            String configurationId,
            String targetCode,
            String catalogId,
            ConfigCenterDtos.Identity identity,
            String expectedRevision,
            JsonNode before,
            JsonNode desired,
            List<Change> changes,
            String action,
            Long rollbackVersionId,
            Instant createdAt,
            Instant expiresAt,
            String status,
            JsonNode targetSnapshot,
            String comment,
            long ownerId) {}
}
