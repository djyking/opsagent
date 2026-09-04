package com.opsagent.ticket;

import com.opsagent.common.core.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提供工单 SLA 详情和全局 SLA 看板数据。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/tickets")
public class SlaController {
    private final SlaService service;

    SlaController(SlaService service) {
        this.service = service;
    }

    @GetMapping("/{id}/sla")
    ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.success(service.detail(id));
    }

    @GetMapping("/sla/overview")
    ApiResponse<List<Map<String, Object>>> overview() {
        return ApiResponse.success(service.overview());
    }
}
