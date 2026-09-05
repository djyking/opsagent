package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 提供 CMDB Lite 服务目录、拓扑和值班排班查询接口。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/platform")
public class ItsmPlatformController {
    private final ItsmPlatformService service;

    ItsmPlatformController(ItsmPlatformService service) {
        this.service = service;
    }

    @GetMapping("/cmdb/cis")
    ApiResponse<List<Map<String, Object>>> cis(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type) {
        return ApiResponse.success(service.cis(keyword, type));
    }

    @GetMapping("/cmdb/cis/{ciCode}")
    ApiResponse<Map<String, Object>> ci(@PathVariable String ciCode) {
        return ApiResponse.success(service.ci(ciCode));
    }

    @PostMapping("/cmdb/cis")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> addCi(@Valid @RequestBody CiRequest request) {
        return ApiResponse.success(service.addCi(request));
    }

    @PutMapping("/cmdb/cis/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> updateCi(
            @PathVariable long id, @Valid @RequestBody CiRequest request) {
        return ApiResponse.success(service.updateCi(id, request));
    }

    @GetMapping("/cmdb/cis/{ciCode}/topology")
    ApiResponse<Map<String, Object>> topology(@PathVariable String ciCode) {
        return ApiResponse.success(service.topology(ciCode));
    }

    @GetMapping("/cmdb/relations")
    ApiResponse<List<Map<String, Object>>> relations() {
        return ApiResponse.success(service.relations());
    }

    @PostMapping("/cmdb/relations")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> addRelation(
            @Valid @RequestBody RelationRequest request) {
        return ApiResponse.success(service.addRelation(request));
    }

    @DeleteMapping("/cmdb/relations/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Void> deleteRelation(@PathVariable long id) {
        service.deleteRelation(id);
        return ApiResponse.success();
    }

    @GetMapping("/oncall/schedules")
    ApiResponse<List<Map<String, Object>>> schedules() {
        return ApiResponse.success(service.schedules());
    }

    @PostMapping("/oncall/schedules")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> addSchedule(
            @Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.success(service.addSchedule(request));
    }

    @PutMapping("/oncall/schedules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> updateSchedule(
            @PathVariable long id, @Valid @RequestBody ScheduleRequest request) {
        return ApiResponse.success(service.updateSchedule(id, request));
    }

    @GetMapping("/oncall/shifts")
    ApiResponse<List<Map<String, Object>>> shifts(
            @RequestParam(required = false) Long scheduleId) {
        return ApiResponse.success(service.shifts(scheduleId));
    }

    @PostMapping("/oncall/shifts")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> addShift(@Valid @RequestBody ShiftRequest request) {
        return ApiResponse.success(service.addShift(request));
    }

    @PutMapping("/oncall/shifts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, Object>> updateShift(
            @PathVariable long id, @Valid @RequestBody ShiftRequest request) {
        return ApiResponse.success(service.updateShift(id, request));
    }

    @DeleteMapping("/oncall/shifts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Void> deleteShift(@PathVariable long id) {
        service.deleteShift(id);
        return ApiResponse.success();
    }

    @GetMapping("/oncall/current")
    ApiResponse<CurrentOnCallResponse> currentOnCall(
            @RequestParam(required = false) String serviceCiCode) {
        return ApiResponse.success(service.currentOnCall(serviceCiCode));
    }

    /**
     * CMDB 配置项维护请求。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record CiRequest(
            @NotBlank @Size(max = 64) String ciCode,
            @NotBlank @Size(max = 128) String ciName,
            @NotBlank @Size(max = 32) String ciType,
            @NotBlank @Size(max = 32) String environment,
            @Size(max = 64) String ownerName,
            @Size(max = 255) String endpoint,
            @NotBlank @Size(max = 16) String status,
            @Size(max = 500) String description) {}

    /**
     * CMDB 依赖关系维护请求。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record RelationRequest(
            @NotBlank @Size(max = 64) String sourceCiCode,
            @NotBlank @Size(max = 64) String targetCiCode,
            @NotBlank @Size(max = 32) String relationType,
            @Size(max = 500) String description) {}

    /**
     * 值班计划维护请求。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record ScheduleRequest(
            @NotBlank @Size(max = 64) String scheduleCode,
            @NotBlank @Size(max = 128) String scheduleName,
            @Size(max = 64) String serviceCiCode,
            @NotBlank @Size(max = 64) String timezone,
            boolean enabled) {}

    /**
     * 值班班次维护请求。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record ShiftRequest(
            @NotNull @Positive Long scheduleId,
            @NotBlank @Size(max = 16) String roleType,
            @NotNull @Positive Long userId,
            @NotBlank @Size(max = 64) String userName,
            @NotNull LocalDateTime startTime,
            @NotNull LocalDateTime endTime) {}
}
