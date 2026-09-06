package com.opsagent.platform;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.SecurityUsers;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 公共演练与内部适配器逐次复核身份、白名单路径、目标、所属关系及事件绑定。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
public class DemoTargetController {
    private final DemoTargetService service;
    private final DemoTargetRepository repository;
    private final InternalActorTokens tokens;
    private final InternalActorAccess access;

    DemoTargetController(
            DemoTargetService service,
            DemoTargetRepository repository,
            @Value("${ops.agent.internal-secret:}") String secret,
            @Value("${ops.agent.auth-url:http://localhost:8101}") String authUrl) {
        this.service = service;
        this.repository = repository;
        tokens = new InternalActorTokens(secret);
        access = new InternalActorAccess(tokens, authUrl);
    }

    @GetMapping("/api/platform/operations/demo/scenarios")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<?> scenarios(
            @RequestParam(defaultValue = DemoTargetDtos.TARGET) String targetCode) {
        var actor = publicActor(targetCode, "manual-" + UUID.randomUUID());
        List<Map<String, Object>> definitions =
                DemoTargetDtos.NOTIFICATION_TARGET.equals(targetCode)
                        ? List.of(
                                Map.of(
                                        "targetCode",
                                        targetCode,
                                        "scenarioCode",
                                        "RABBITMQ_CONSUMER_PAUSED",
                                        "title",
                                        "RabbitMQ 通知消费者暂停",
                                        "expectedHttpStatus",
                                        503,
                                        "action",
                                        "RESTORE_QUEUE_CONSUMER"))
                        : List.of(
                                Map.of(
                                        "targetCode",
                                        targetCode,
                                        "scenarioCode",
                                        "NACOS_REDIS_CONFIG_DRIFT",
                                        "title",
                                        "Nacos Redis 配置漂移",
                                        "expectedHttpStatus",
                                        503,
                                        "action",
                                        "RESTORE_CONFIGURATION"),
                                Map.of(
                                        "targetCode",
                                        targetCode,
                                        "scenarioCode",
                                        "SENTINEL_RULE_REGRESSION",
                                        "title",
                                        "Sentinel 限流规则回退",
                                        "expectedHttpStatus",
                                        429,
                                        "action",
                                        "RESTORE_FLOW_RULE"));
        return ApiResponse.success(
                Map.of(
                        "definitions",
                        definitions,
                        "incidents",
                        service.list(actor),
                        "defaultTtlSeconds",
                        600,
                        "targetCode",
                        targetCode));
    }

    @PostMapping("/api/platform/operations/demo/scenarios")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<DemoTargetDtos.Incident> create(
            @Valid @RequestBody DemoTargetDtos.CreateScenario request) {
        return ApiResponse.success(
                service.create(
                        request,
                        publicActor(
                                DemoTargetDtos.scenarioTarget(request.scenarioCode()),
                                "manual-" + UUID.randomUUID())));
    }

    @PostMapping("/api/platform/operations/demo/actions")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<ObjectNode> manualAction(@Valid @RequestBody DemoTargetDtos.Action request) {
        String runId =
                "manual-"
                        + UUID.nameUUIDFromBytes(
                                request.idempotencyKey()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String targetCode = repository.get(request.incidentId()).targetCode();
        return ApiResponse.success(service.action(request, publicActor(targetCode, runId)));
    }

    @GetMapping("/api/platform/operations/demo/target")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<ObjectNode> target(
            @RequestParam(defaultValue = DemoTargetDtos.TARGET) String targetCode) {
        return ApiResponse.success(
                service.snapshot(publicActor(targetCode, "manual-" + UUID.randomUUID())));
    }

    @GetMapping("/api/platform/operations/demo/incidents/{incidentId}/evidence")
    @PreAuthorize("isAuthenticated()")
    ApiResponse<ObjectNode> evidence(@PathVariable String incidentId) {
        String targetCode = repository.get(incidentId).targetCode();
        return ApiResponse.success(
                service.evidence(
                        incidentId, publicActor(targetCode, "manual-" + UUID.randomUUID())));
    }

    @GetMapping("/internal/platform/demo-targets/{targetKey}/snapshot")
    ApiResponse<ObjectNode> internalSnapshot(
            @PathVariable String targetKey,
            @RequestHeader(value = "Authorization", required = false) String header) {
        var actor = internalActor(targetKey, header);
        try (var ignored = InternalActorAccess.open(actor)) {
            return ApiResponse.success(service.snapshot(actor));
        }
    }

    @PostMapping("/internal/platform/demo-targets/{targetKey}/scenarios")
    ApiResponse<DemoTargetDtos.Incident> internalCreate(
            @PathVariable String targetKey,
            @RequestHeader(value = "Authorization", required = false) String header,
            @Valid @RequestBody DemoTargetDtos.CreateScenario request) {
        var actor = internalActor(targetKey, header);
        try (var ignored = InternalActorAccess.open(actor)) {
            return ApiResponse.success(service.create(request, actor));
        }
    }

    @PostMapping("/internal/platform/demo-targets/{targetKey}/actions")
    ApiResponse<ObjectNode> internalAction(
            @PathVariable String targetKey,
            @RequestHeader(value = "Authorization", required = false) String header,
            @Valid @RequestBody DemoTargetDtos.Action request) {
        var actor = internalActor(targetKey, header);
        try (var ignored = InternalActorAccess.open(actor)) {
            return ApiResponse.success(service.action(request, actor));
        }
    }

    @GetMapping("/internal/platform/demo-targets/{targetKey}/incidents/{incidentId}")
    ApiResponse<DemoTargetDtos.Incident> internalIncident(
            @PathVariable String targetKey,
            @RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable String incidentId) {
        return ApiResponse.success(
                service.authorized(incidentId, internalActor(targetKey, header)));
    }

    @GetMapping("/internal/platform/demo-targets/{targetKey}/incidents/{incidentId}/evidence")
    ApiResponse<ObjectNode> internalEvidence(
            @PathVariable String targetKey,
            @RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable String incidentId) {
        return ApiResponse.success(service.evidence(incidentId, internalActor(targetKey, header)));
    }

    @GetMapping("/internal/platform/demo-targets/{targetKey}/incident-owner")
    ApiResponse<DemoTargetDtos.IncidentOwner> incidentOwner(
            @PathVariable String targetKey,
            @RequestHeader(value = "Authorization", required = false) String header,
            @RequestParam String targetCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startsAt) {
        var actor = tokens.verify(header, "platform");
        String pathTarget = DemoTargetDtos.targetForPath(targetKey);
        if (actor.userId() != Long.MAX_VALUE
                || !"alertmanager-linker".equals(actor.username())
                || !actor.roles().equals(List.of("SYSTEM"))
                || !pathTarget.equals(actor.targetCode())
                || !pathTarget.equals(targetCode)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "DEMO_OWNER_RESOLVER_FORBIDDEN");
        }
        return ApiResponse.success(repository.owner(targetCode, startsAt));
    }

    private InternalActorTokens.Context internalActor(String targetKey, String header) {
        String targetCode = DemoTargetDtos.targetForPath(targetKey);
        var actor = access.verify(header, "platform");
        if (!targetCode.equals(actor.targetCode()))
            throw new BusinessException(ErrorCode.FORBIDDEN, "DEMO_TARGET_MISMATCH");
        return actor;
    }

    private InternalActorTokens.Context publicActor(String targetCode, String runId) {
        if (!DemoTargetDtos.TARGETS.contains(targetCode))
            throw new BusinessException(ErrorCode.FORBIDDEN, "DEMO_TARGET_NOT_ALLOWED");
        var principal = SecurityUsers.current();
        var context =
                new InternalActorTokens.Context(
                        principal.userId(),
                        principal.username(),
                        principal.roles().stream()
                                .map(role -> role.replaceFirst("^ROLE_", ""))
                                .toList(),
                        runId,
                        targetCode,
                        Instant.now().plusSeconds(90));
        return access.verify("Bearer " + tokens.issue("platform", context), "platform");
    }
}
