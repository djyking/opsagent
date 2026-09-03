package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 提供平台运行信息和管理员配置视图。
 *
 * @author heyu
 * @since 2026/8/30
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {
    private final PlatformAuditService auditService;

    PlatformController(PlatformAuditService auditService) {
        this.auditService = auditService;
    }

    @Value("${spring.application.name}")
    String service;

    @GetMapping("/info")
    ApiResponse<Map<String, Object>> info() {
        return ApiResponse.success(
                Map.of("service", service, "time", Instant.now(), "status", "UP"));
    }

    @GetMapping("/admin/configuration")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> configuration() {
        return ApiResponse.success(
                Map.of(
                        "message",
                        "敏感配置值不会通过接口返回",
                        "middlewareIntegration",
                        "configuration-driven"));
    }

    @GetMapping("/admin/audits")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<List<Map<String, Object>>> audits(
            @RequestParam(required = false) String bizId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(auditService.audits(bizId, limit));
    }
}
