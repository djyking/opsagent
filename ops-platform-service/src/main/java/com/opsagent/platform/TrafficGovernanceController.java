package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Native Sentinel management facade, without dashboard iframes or in-memory-only writes.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/platform/traffic")
@PreAuthorize("isAuthenticated()")
class TrafficGovernanceController {
    private final TrafficGovernanceService service;
    private final TrafficObservationService observation;

    TrafficGovernanceController(
            TrafficGovernanceService service, TrafficObservationService observation) {
        this.service = service;
        this.observation = observation;
    }

    @GetMapping("/overview")
    ResponseEntity<ApiResponse<TrafficGovernanceDtos.Overview>> overview() {
        return response(observation.overview());
    }

    @GetMapping
    ResponseEntity<ApiResponse<TrafficGovernanceDtos.Workspace>> workspace(
            @RequestParam(required = false) String ciCode) {
        return response(service.workspace(ciCode));
    }

    @GetMapping("/summary")
    ResponseEntity<ApiResponse<TrafficGovernanceDtos.Summary>> summary(
            @RequestParam(required = false) String ciCode) {
        return response(service.summary(ciCode));
    }

    @GetMapping("/rules/{type}")
    ResponseEntity<ApiResponse<TrafficGovernanceDtos.RuleSet>> rules(@PathVariable String type) {
        return response(service.ruleSet(type));
    }

    @GetMapping("/history")
    ResponseEntity<ApiResponse<TrafficGovernanceDtos.History>> history(
            @RequestParam(defaultValue = "FLOW") String type) {
        return response(service.history(type));
    }

    @PostMapping("/rules/{type}/validate")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<TrafficGovernanceDtos.Validated> validate(
            @PathVariable String type, @Valid @RequestBody TrafficGovernanceDtos.Validate request) {
        return ApiResponse.success(service.validate(type, request.rules()));
    }

    @PostMapping("/rules/{type}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<TrafficGovernanceDtos.Result> publish(
            @PathVariable String type, @Valid @RequestBody TrafficGovernanceDtos.Publish request) {
        return ApiResponse.success(service.publish(type, request));
    }

    @PostMapping("/rules/{type}/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<TrafficGovernanceDtos.Result> rollback(
            @PathVariable String type, @Valid @RequestBody TrafficGovernanceDtos.Rollback request) {
        return ApiResponse.success(service.rollback(type, request));
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(value));
    }
}
