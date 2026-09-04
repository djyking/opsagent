package com.opsagent.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.ApiResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 接收 Alertmanager Webhook，并为管理员提供活动告警查询。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
public class AlertmanagerController {
    private final AlertmanagerService service;

    AlertmanagerController(AlertmanagerService service) {
        this.service = service;
    }

    @PostMapping("/api/integrations/alertmanager/webhook")
    ApiResponse<List<Map<String, Object>>> webhook(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode payload) {
        service.authenticate(authorization);
        return ApiResponse.success(service.receive(payload));
    }

    @GetMapping("/api/tickets/alerts")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    ApiResponse<List<Map<String, Object>>> alerts(
            @RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(status));
    }
}
