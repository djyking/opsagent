package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 统一观测聚合入口，管理员维护拓扑，普通运维只能读取和执行只读巡检。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/platform/observability")
class ObservabilityController {
    private final TopologyAggregationService topology;
    private final ObservabilityInspectionService inspections;

    ObservabilityController(
            TopologyAggregationService topology, ObservabilityInspectionService inspections) {
        this.topology = topology;
        this.inspections = inspections;
    }

    @GetMapping({"/topology", "/wallboard"})
    ApiResponse<Map<String, Object>> topology(
            @RequestParam(defaultValue = "ALL") String environment,
            @RequestParam(defaultValue = "15m") String timeRange,
            @RequestParam(defaultValue = "CONFIGURED") String mode) {
        return ApiResponse.success(topology.topology(environment, timeRange, mode));
    }

    @GetMapping("/services/{ciCode}")
    ApiResponse<Map<String, Object>> service(
            @PathVariable String ciCode, @RequestParam(defaultValue = "15m") String timeRange) {
        return ApiResponse.success(topology.detail(ciCode, timeRange));
    }

    @PutMapping("/topology/layout")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Void> layout(@Valid @RequestBody ObservabilityDtos.Layout request) {
        topology.saveLayout(request);
        return ApiResponse.success();
    }

    @GetMapping("/topology/layout")
    ApiResponse<Map<String, Object>> currentLayout(
            @RequestParam(defaultValue = "ALL") String environment) {
        return ApiResponse.success(topology.currentLayout(environment));
    }

    @PutMapping("/topology/layout/personal")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Void> personalLayout(@Valid @RequestBody ObservabilityDtos.Layout request) {
        topology.savePersonalLayout(request);
        return ApiResponse.success();
    }

    @DeleteMapping("/topology/layout/personal")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<Void> resetPersonalLayout(@RequestParam String environment) {
        topology.resetPersonalLayout(environment);
        return ApiResponse.success();
    }

    @GetMapping("/inspections")
    ApiResponse<Map<String, Object>> inspections(
            @RequestParam(defaultValue = "ALL") String environment) {
        return ApiResponse.success(inspections.overview(environment));
    }

    @GetMapping("/inspections/{ciCode}/history")
    ApiResponse<Map<String, Object>> history(@PathVariable String ciCode) {
        return ApiResponse.success(inspections.history(ciCode));
    }

    @PostMapping("/inspections/{ciCode}/run")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','DEMO')")
    ApiResponse<Map<String, Object>> inspect(@PathVariable String ciCode) {
        return ApiResponse.success(inspections.run(ciCode));
    }
}
