package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 无任意namespace/dataId入口的配置中心，所有修改仅ADMIN可用。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@Validated
@RequestMapping("/api/platform/configuration/managed")
@PreAuthorize("isAuthenticated()")
class ManagedConfigurationController {
    private final ManagedConfigurationService service;

    ManagedConfigurationController(ManagedConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<ApiResponse<Map<String, List<ManagedConfigurationDtos.Definition>>>> list() {
        return response(Map.of("items", service.definitions()));
    }

    @GetMapping("/{id}")
    ResponseEntity<ApiResponse<ManagedConfigurationDtos.Detail>> detail(@PathVariable String id) {
        return response(service.detail(id));
    }

    @GetMapping("/{id}/history")
    ResponseEntity<ApiResponse<ManagedConfigurationDtos.HistoryPage>> history(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return response(service.history(id, page, size));
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<ManagedConfigurationDtos.Validated> validate(
            @PathVariable String id,
            @Valid @RequestBody ManagedConfigurationDtos.Validate request) {
        return ApiResponse.success(service.validate(id, request.content()));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<ManagedConfigurationDtos.Result> publish(
            @PathVariable String id, @Valid @RequestBody ManagedConfigurationDtos.Publish request) {
        throw new com.opsagent.common.core.BusinessException(
                com.opsagent.common.core.ErrorCode.FORBIDDEN,
                "配置发布须先创建不可变变更提案并经AI自动化的现有审批，直接发布入口已关闭。");
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<ManagedConfigurationDtos.Result> rollback(
            @PathVariable String id,
            @Valid @RequestBody ManagedConfigurationDtos.Rollback request) {
        throw new com.opsagent.common.core.BusinessException(
                com.opsagent.common.core.ErrorCode.FORBIDDEN, "配置回退须创建新的回退提案并通过现有审批，直接回退入口已关闭。");
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(value));
    }
}
