package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.constraints.Min;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Authenticated, read-only configuration inventory; writes retain existing governed APIs.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@Validated
@RequestMapping("/api/platform/config-center")
@PreAuthorize("isAuthenticated()")
class ConfigCenterController {
    private final ConfigCenterService service;

    ConfigCenterController(ConfigCenterService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<ApiResponse<ConfigCenterDtos.Catalog>> list(
            @RequestParam(required = false) String ciCode) {
        return response(service.catalog(ciCode));
    }

    @GetMapping("/summary")
    ResponseEntity<ApiResponse<ConfigCenterDtos.Summary>> summary(
            @RequestParam(required = false) String ciCode) {
        return response(service.summary(ciCode));
    }

    @GetMapping("/{id}")
    ResponseEntity<ApiResponse<ConfigCenterDtos.Detail>> detail(@PathVariable String id) {
        return response(service.detail(id));
    }

    @GetMapping("/{id}/history")
    ResponseEntity<ApiResponse<ConfigCenterDtos.History>> history(@PathVariable String id) {
        return response(service.history(id));
    }

    @GetMapping("/{id}/diff")
    ResponseEntity<ApiResponse<ConfigCenterDtos.Diff>> diff(
            @PathVariable String id, @RequestParam @Min(1) long versionId) {
        return response(service.diff(id, versionId));
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(value));
    }
}
