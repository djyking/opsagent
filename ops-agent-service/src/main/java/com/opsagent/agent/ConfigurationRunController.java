package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.SecurityUsers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 复用 AgentRun/审批/执行器承载配置提案；不依赖模型，也不创建虚假事件。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class ConfigurationRunController {
    private final AgentClients clients;
    private final AgentStore store;

    ConfigurationRunController(AgentClients clients, AgentStore store) {
        this.clients = clients;
        this.store = store;
    }

    @PostMapping("/api/automation/configuration-runs")
    @PreAuthorize("hasRole('ADMIN')")
    ApiResponse<Map<String, String>> create(@Valid @RequestBody Request request) {
        var principal = SecurityUsers.current();
        var actor = clients.current(principal, UUID.randomUUID().toString());
        if (!actor.roles().contains("ADMIN")) throw AgentClients.denied();
        JsonNode proposal =
                clients.call(
                        "platform",
                        "/internal/platform/configuration/proposals/" + request.proposalId(),
                        "GET",
                        null,
                        actor);
        if (!request.immutableDigest().equals(proposal.path("immutableDigest").asText())
                || !request.proposalId().equals(proposal.path("proposalId").asText())
                || !AgentTargets.ORDER.equals(proposal.path("targetCode").asText())
                || !"order-business".equals(proposal.path("configurationId").asText()))
            throw AgentClients.denied();
        Instant expires = Instant.parse(proposal.path("expiresAt").asText());
        if (!expires.isAfter(Instant.now())) throw AgentJson.invalid("配置提案已过期，请重新校验");
        if (!store.version("configuration-change").equals(WorkflowGraph.configurationChange())) {
            throw AgentJson.invalid("配置工作流已变更，需由管理员核对固定安全流程");
        }
        ObjectNode snapshot =
                AgentJson.object()
                        .put("toolRegistryVersion", "configuration-tools-v1")
                        .put("policyVersion", "exact-configuration-approval-v1");
        snapshot.set("configurationProposal", proposal);
        var tools = snapshot.putArray("tools");
        for (JsonNode tool : AgentTools.schemas()) {
            if (tool.path("function").path("name").asText().equals("config_change_apply"))
                tools.add(tool);
        }
        snapshot.set(
                "model", AgentJson.object().put("provider", "NONE").put("name", "受控配置工作流，不调用模型"));
        ObjectNode state =
                AgentJson.object()
                        .put("runId", actor.runId())
                        .put("ticketId", 0)
                        .put("incidentId", "")
                        .put("targetCode", AgentTargets.ORDER)
                        .put("trigger", "configuration:" + request.proposalId())
                        .put(
                                "deadline",
                                expires.isBefore(actor.validUntil())
                                        ? expires.toString()
                                        : actor.validUntil().toString())
                        .put("turns", 0)
                        .put("toolCount", 0)
                        .put("tokens", 0)
                        .put("ticketResolved", false);
        state.set("actor", clients.actorJson(actor));
        state.withObject("/outputs")
                .set(
                        "proposal",
                        AgentJson.object()
                                .put("proposalId", request.proposalId())
                                .put("immutableDigest", request.immutableDigest()));
        return ApiResponse.success(
                Map.of(
                        "id",
                        store.create(
                                "configuration-change",
                                "configuration:" + principal.userId() + ":" + request.requestId(),
                                principal.userId(),
                                snapshot,
                                state)));
    }

    /**
     * @author heyu
     */
    record Request(
            @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String proposalId,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String immutableDigest,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{10,80}") String requestId) {}
}
