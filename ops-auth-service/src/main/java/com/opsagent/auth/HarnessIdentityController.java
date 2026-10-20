package com.opsagent.auth;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Application-authenticated bridge for existing login identities and bounded async delegation.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/internal/harness")
@ConditionalOnProperty(name = "ops.harness.identity.enabled", havingValue = "true")
public class HarnessIdentityController {
    private final HarnessIdentityService identities;

    public HarnessIdentityController(HarnessIdentityService identities) {
        this.identities = identities;
    }

    @PostMapping("/introspect")
    public ResponseEntity<?> introspect(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody Introspect request) {
        return response(
                identities.introspect(authorization, request.projectId(), request.userToken()));
    }

    @PostMapping("/delegations")
    public ResponseEntity<?> delegate(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody Delegate request) {
        return response(
                identities.delegate(
                        authorization,
                        request.projectId(),
                        request.userToken(),
                        request.runId(),
                        request.deadline()));
    }

    @GetMapping("/delegations/{id}")
    public ResponseEntity<?> current(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String id,
            @RequestParam String runId) {
        return response(identities.current(authorization, id, runId));
    }

    @PostMapping("/delegations/{id}/token")
    public ResponseEntity<?> token(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String id,
            @Valid @RequestBody Mint request) {
        return response(identities.token(authorization, id, request.runId(), request.audience()));
    }

    private ResponseEntity<?> response(Object value) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(ApiResponse.success(value));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> failure(Exception failure) {
        int status =
                failure instanceof com.opsagent.common.core.BusinessException business
                        ? business.getErrorCode().code() / 100
                        : 400;
        if (status < 400 || status > 599) status = 503;
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(ApiResponse.failure(status * 100, "HARNESS_IDENTITY_REQUEST_REJECTED"));
    }

    /**
     * @author heyu
     */
    public record Introspect(
            @NotBlank @Size(max = 64) String projectId,
            @NotBlank @Size(max = 10000) String userToken) {}

    /**
     * @author heyu
     */
    public record Delegate(
            @NotBlank @Size(max = 64) String projectId,
            @NotBlank @Size(max = 10000) String userToken,
            @NotBlank @Size(max = 128) String runId,
            @NotNull Instant deadline) {}

    /**
     * @author heyu
     */
    public record Mint(
            @NotBlank @Size(max = 128) String runId, @NotBlank @Size(max = 64) String audience) {}
}
