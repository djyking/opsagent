package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 带身份与时间边界的真实调用、实例及历史入口。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/platform/observability/v3")
class ObservabilityV3Controller {
    private final ObservabilityV3Service service;

    ObservabilityV3Controller(ObservabilityV3Service service) {
        this.service = service;
    }

    @GetMapping("/topology")
    ApiResponse<Map<String, Object>> topology(
            @RequestParam(defaultValue = "ALL") String environment,
            @RequestParam(defaultValue = "15m") String timeRange,
            @RequestParam(defaultValue = "CONFIGURED") String mode) {
        return ApiResponse.success(service.topology(environment, timeRange, mode));
    }

    @GetMapping("/services/{ciCode}/instances")
    ApiResponse<Map<String, Object>> instances(
            @PathVariable String ciCode, @RequestParam(defaultValue = "15m") String timeRange) {
        return ApiResponse.success(service.instances(ciCode, timeRange));
    }

    @GetMapping("/traces")
    ApiResponse<Map<String, Object>> traces(
            @RequestParam String ciCode,
            @RequestParam(defaultValue = "ALL") String environment,
            @RequestParam(defaultValue = "15m") String timeRange,
            @RequestParam(required = false) String targetCiCode) {
        return ApiResponse.success(service.search(ciCode, environment, timeRange, targetCiCode));
    }

    @GetMapping("/traces/{traceId}")
    ApiResponse<Map<String, Object>> trace(
            @PathVariable String traceId,
            @RequestParam String ciCode,
            @RequestParam(defaultValue = "ALL") String environment) {
        return ApiResponse.success(service.trace(traceId, environment, ciCode));
    }

    @GetMapping("/history")
    ApiResponse<Map<String, Object>> history(
            @RequestParam(defaultValue = "ALL") String environment,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        return ApiResponse.success(service.history(environment, from, to));
    }

    @GetMapping("/history/{id}")
    ApiResponse<Map<String, Object>> history(@PathVariable String id) {
        return ApiResponse.success(service.history(id));
    }

    @GetMapping("/differences")
    ApiResponse<Map<String, Object>> differences(
            @RequestParam(defaultValue = "ALL") String environment,
            @RequestParam(defaultValue = "15m") String timeRange) {
        return ApiResponse.success(service.differences(environment, timeRange));
    }

    @PutMapping("/differences/{id}/decision")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Void> decision(@PathVariable String id, @Valid @RequestBody Decision request) {
        service.decision(id, request.decision(), request.note(), request.ignoreUntil());
        return ApiResponse.success();
    }

    /**
     * @author heyu
     */
    record Decision(
            @NotBlank @Pattern(regexp = "ACKNOWLEDGED|IGNORE") String decision,
            @NotBlank @Size(max = 500) String note,
            Instant ignoreUntil) {}
}
