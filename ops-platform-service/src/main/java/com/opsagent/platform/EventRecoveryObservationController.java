package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * A recovery watch is bound to an authorized ticket and its server-recorded latest result.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/platform/observability/services/{ciCode}")
@PreAuthorize("hasAnyRole('ADMIN','OPS')")
class EventRecoveryObservationController {
    private final EventRecoveryObservationService recovery;
    private final TopologyAggregationService topology;
    private final RestClient tickets;
    private final HttpServletRequest request;

    EventRecoveryObservationController(
            EventRecoveryObservationService recovery,
            TopologyAggregationService topology,
            @Value("${ops.event.ticket-url:http://localhost:8102}") String ticketUrl,
            HttpServletRequest request) {
        this.recovery = recovery;
        this.topology = topology;
        this.request = request;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        tickets = RestClient.builder().baseUrl(ticketUrl).requestFactory(factory).build();
    }

    @PostMapping("/recovery-observations")
    ApiResponse<Void> start(
            @PathVariable String ciCode,
            @RequestParam String environment,
            @RequestParam long ticketId) {
        authorize(ticketId, ciCode);
        var event = read("/api/tickets/{id}/event-lifecycle", ticketId);
        String recorded = event.path("result").path("createTime").asText();
        if (recorded.isBlank()
                || !event.path("closed").isNull() && !event.path("closed").isMissingNode())
            throw new BusinessException(ErrorCode.CONFLICT, "事件没有当前处理结果或已经关闭，不能启动恢复观察");
        Instant resultAt = LocalDateTime.parse(recorded).atZone(ZoneId.systemDefault()).toInstant();
        recovery.start(ticketId, ciCode, environment, resultAt, SecurityUsers.current().userId());
        return ApiResponse.success();
    }

    @DeleteMapping("/recovery-observations")
    ApiResponse<Void> stop(
            @PathVariable String ciCode,
            @RequestParam String environment,
            @RequestParam long ticketId) {
        authorize(ticketId, ciCode);
        recovery.stop(ticketId, ciCode, environment);
        return ApiResponse.success();
    }

    @GetMapping("/recovery-evidence")
    ApiResponse<Map<String, Object>> evidence(
            @PathVariable String ciCode,
            @RequestParam String environment,
            @RequestParam long ticketId) {
        authorize(ticketId, ciCode);
        var detail = topology.detail(ciCode, "5m");
        if (!(detail.get("node") instanceof Map<?, ?> node)
                || !environment.equals(node.get("environment")))
            throw new BusinessException(ErrorCode.CONFLICT, "恢复证据目标与环境不匹配");
        return ApiResponse.success(
                Map.of(
                        "targetCode",
                        ciCode,
                        "environment",
                        environment,
                        "ticketId",
                        ticketId,
                        "generatedAt",
                        Instant.now(),
                        "scope",
                        "CURRENT",
                        "currentNode",
                        node,
                        "alertsAvailable",
                        Boolean.TRUE.equals(detail.get("alertsAvailable")),
                        "inspections",
                        recovery.history(ticketId, ciCode, environment)));
    }

    private void authorize(long ticketId, String code) {
        if (ticketId <= 0
                || !code.equals(
                        read("/api/tickets/{id}", ticketId).path("affectedCiCode").asText()))
            throw new BusinessException(ErrorCode.CONFLICT, "恢复观察必须绑定同一张可见工单及实际目标");
    }

    private JsonNode read(String path, long id) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new BusinessException(ErrorCode.FORBIDDEN, "恢复观察需要已登录的处置身份");
        var response =
                tickets.get()
                        .uri(path, id)
                        .header("Authorization", authorization)
                        .retrieve()
                        .body(JsonNode.class);
        if (response == null || response.path("code").asInt(-1) != 0)
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前用户不可读取对应事件");
        return response.path("data");
    }
}
