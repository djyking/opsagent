package com.opsagent.common.security;

import com.opsagent.common.core.ApiResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated fixed-field runtime projection, without an arbitrary key or path parameter.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
public class RuntimeConfigurationController {
    private final RuntimeConfigurationSnapshot snapshots;

    public RuntimeConfigurationController(RuntimeConfigurationSnapshot snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping("/api/runtime/configuration")
    public ResponseEntity<ApiResponse<RuntimeConfigurationSnapshot.Snapshot>> read() {
        var actor = SecurityUsers.current();
        if (actor.userId() <= 0
                || actor.roles().stream()
                        .noneMatch(
                                role ->
                                        java.util.Set.of("ADMIN", "ROLE_ADMIN", "OPS", "ROLE_OPS")
                                                .contains(role)))
            throw new com.opsagent.common.core.BusinessException(
                    com.opsagent.common.core.ErrorCode.FORBIDDEN, "运行配置需要管理员或运维权限");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(snapshots.snapshot()));
    }
}
