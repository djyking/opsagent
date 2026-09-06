package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Public proposal creation and signed Agent execution; no public approval bypass.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class ConfigurationProposalController {
    private final ConfigurationProposalService service;
    private final InternalActorAccess access;

    ConfigurationProposalController(
            ConfigurationProposalService service,
            @Value("${ops.agent.internal-secret:}") String secret,
            @Value("${ops.agent.auth-url:http://localhost:8101}") String authUrl) {
        this.service = service;
        access = new InternalActorAccess(new InternalActorTokens(secret), authUrl);
    }

    @PostMapping("/api/platform/config-center/{id}/proposals")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<ApiResponse<ConfigurationProposalDtos.Proposal>> create(
            @PathVariable String id, @Valid @RequestBody ConfigurationProposalDtos.Create request) {
        return response(service.propose(id, request));
    }

    @GetMapping("/api/platform/config-center/proposals/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<ApiResponse<ConfigurationProposalDtos.Proposal>> read(@PathVariable String id) {
        return response(service.read(id));
    }

    @GetMapping("/internal/platform/configuration/proposals/{id}")
    ResponseEntity<ApiResponse<ConfigurationProposalDtos.Proposal>> internalRead(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String header) {
        var actor = actor(header);
        try (var ignored = InternalActorAccess.open(actor)) {
            return response(service.read(id));
        }
    }

    @PostMapping("/internal/platform/configuration/proposals/{id}/apply")
    ResponseEntity<ApiResponse<ManagedConfigurationDtos.Result>> apply(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String header,
            @Valid @RequestBody ConfigurationProposalDtos.Apply request) {
        var actor = actor(header);
        try (var ignored = InternalActorAccess.open(actor)) {
            return response(service.apply(id, request.immutableDigest(), actor));
        }
    }

    private InternalActorTokens.Context actor(String header) {
        var actor = access.verify(header, "platform");
        if (!DemoTargetDtos.TARGET.equals(actor.targetCode()))
            throw new BusinessException(ErrorCode.FORBIDDEN, "CONFIG_TARGET_MISMATCH");
        return actor;
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T result) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result));
    }
}
