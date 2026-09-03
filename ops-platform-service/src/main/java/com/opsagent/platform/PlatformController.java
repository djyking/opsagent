package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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
    private final MonitoringService monitoringService;

    PlatformController(PlatformAuditService auditService, MonitoringService monitoringService) {
        this.auditService = auditService;
        this.monitoringService = monitoringService;
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
    ApiResponse<Map<String, Object>> audits(
            @RequestParam(required = false) String bizId,
            @RequestParam(required = false) String operation,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(auditService.auditPage(bizId, operation, pageNum, pageSize));
    }

    @GetMapping("/admin/notifications")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> notifications(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(auditService.notificationPage(status, pageNum, pageSize));
    }

    @PutMapping("/admin/notifications/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> notificationStatus(
            @PathVariable long id, @RequestParam String status) {
        return ApiResponse.success(auditService.updateNotification(id, status));
    }

    @PutMapping("/admin/notifications/read-all")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> readAllNotifications() {
        return ApiResponse.success(auditService.markAllNotificationsRead());
    }

    @GetMapping("/monitor/summary")
    ApiResponse<Map<String, Object>> monitorSummary() {
        return ApiResponse.success(monitoringService.summary());
    }
}
