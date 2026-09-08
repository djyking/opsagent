package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.SecurityUsers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 自动化工作区 API，读写范围分别验证。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Validated
@RestController
@RequestMapping("/api/automation")
class AgentController {
    private final AgentService service;
    private final AgentStore store;
    private final AgentWorkspace workspace;

    AgentController(AgentService service, AgentStore store, AgentWorkspace workspace) {
        this.service = service;
        this.store = store;
        this.workspace = workspace;
    }

    @GetMapping("/tickets/{ticketId}/workspace")
    ApiResponse<JsonNode> workspace(@PathVariable @Min(1) long ticketId) {
        return ApiResponse.success(workspace.view(ticketId));
    }

    @GetMapping("/summary")
    ApiResponse<?> summary() {
        return ApiResponse.success(service.summary());
    }

    @GetMapping("/models")
    ApiResponse<JsonNode> models() {
        return ApiResponse.success(service.models());
    }

    @PostMapping("/models/{provider}/probe")
    ApiResponse<JsonNode> probe(@PathVariable String provider) {
        return ApiResponse.success(service.probe(provider.toUpperCase(java.util.Locale.ROOT)));
    }

    @GetMapping("/tools")
    ApiResponse<Object> tools() {
        return ApiResponse.success(
                Map.of(
                        "tools",
                        AgentTools.schemas(),
                        "limits",
                        service.limits(),
                        "approvalRequired",
                        AgentTools.HIGH));
    }

    @GetMapping("/definitions")
    ApiResponse<?> definitions() {
        return ApiResponse.success(store.definitions());
    }

    @GetMapping("/definitions/{id}")
    ApiResponse<JsonNode> definition(@PathVariable String id) {
        return ApiResponse.success(store.definition(id));
    }

    @PutMapping("/definitions/{id}")
    ApiResponse<Void> draft(@PathVariable String id, @Valid @RequestBody Draft request) {
        service.admin();
        store.draft(id, request.name(), request.graph(), request.revision());
        return ApiResponse.success();
    }

    @PostMapping("/definitions/validate")
    ApiResponse<Void> validate(@RequestBody JsonNode graph) {
        WorkflowGraph.validate(graph);
        return ApiResponse.success();
    }

    @PostMapping("/definitions/{id}/publish")
    ApiResponse<?> publish(@PathVariable String id, @Valid @RequestBody Publish request) {
        service.admin();
        return ApiResponse.success(Map.of("version", store.publish(id, request.revision())));
    }

    @PostMapping("/runs")
    ApiResponse<?> create(@Valid @RequestBody CreateRun request) {
        return ApiResponse.success(
                Map.of(
                        "id",
                        service.manual(
                                request.ticketId(),
                                request.definitionId(),
                                request.provider(),
                                request.requestId())));
    }

    @GetMapping("/runs")
    ApiResponse<?> runs(
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size,
            @RequestParam(required = false) @Min(1) Long ticketId,
            @RequestParam(required = false) @Size(max = 36) String incidentId) {
        return ApiResponse.success(service.runs(page, size, ticketId, incidentId));
    }

    @GetMapping("/runs/{id}")
    ApiResponse<JsonNode> detail(@PathVariable String id) {
        return ApiResponse.success(service.detail(id));
    }

    @GetMapping("/runs/{id}/events")
    ApiResponse<List<JsonNode>> events(
            @PathVariable String id, @RequestParam(defaultValue = "0") @Min(0) long after) {
        service.detail(id);
        return ApiResponse.success(store.events(id, after));
    }

    @GetMapping("/runs/{id}/usage")
    ApiResponse<JsonNode> usage(@PathVariable String id) {
        return ApiResponse.success(service.usage(id));
    }

    @PostMapping("/runs/{id}/cancel")
    ApiResponse<Void> cancel(@PathVariable String id) {
        service.own(id);
        store.cancel(id, SecurityUsers.current().userId());
        return ApiResponse.success();
    }

    @PostMapping("/runs/{id}/pause")
    ApiResponse<Void> pause(@PathVariable String id) {
        service.own(id);
        store.requestPause(id, SecurityUsers.current().userId());
        return ApiResponse.success();
    }

    @PostMapping("/runs/{id}/resume")
    ApiResponse<Void> resume(@PathVariable String id) {
        service.own(id);
        store.resume(id, SecurityUsers.current().userId());
        return ApiResponse.success();
    }

    @GetMapping("/approvals/pending")
    ApiResponse<?> pendingApprovals(@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return ApiResponse.success(service.pendingApprovals(limit));
    }

    @PostMapping("/approvals/{id}/decision")
    ApiResponse<Void> decide(@PathVariable String id, @Valid @RequestBody Decision decision) {
        service.decide(
                id,
                decision.revision(),
                decision.argsHash(),
                decision.approved(),
                decision.reason());
        return ApiResponse.success();
    }

    /**
     * @author heyu
     */
    record Draft(
            @NotBlank @Size(max = 120) String name,
            @Min(0) int revision,
            @NotNull JsonNode graph) {}

    /**
     * @author heyu
     */
    record Publish(@Min(1) int revision) {}

    /**
     * @author heyu
     */
    record CreateRun(
            @Min(1) long ticketId,
            @NotBlank String definitionId,
            @NotBlank String provider,
            @NotBlank String requestId) {}

    /**
     * @author heyu
     */
    record Decision(
            @Min(1) int revision,
            @NotBlank String argsHash,
            boolean approved,
            @NotNull @Size(max = 500) String reason) {}
}
