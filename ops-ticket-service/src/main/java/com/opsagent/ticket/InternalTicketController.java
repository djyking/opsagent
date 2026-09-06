package com.opsagent.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.*;
import com.opsagent.common.security.*;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 内部工具入口逐次复核 Auth 主体，并保留工单自身的数据权限。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/internal/agent")
class InternalTicketController {
    private final InternalActorAccess access;
    private final TicketService service;
    private final InternalTicketTools tools;
    private final AlertEpisodeMapper episodes;

    InternalTicketController(
            TicketService service,
            InternalTicketTools tools,
            AlertEpisodeMapper episodes,
            @Value("${ops.agent.internal-secret:${OPS_AGENT_INTERNAL_SECRET:}}") String secret,
            @Value("${ops.agent.auth-url:${OPS_AUTH_INTERNAL_URL:http://localhost:8101}}")
                    String authUrl) {
        this.service = service;
        this.tools = tools;
        this.episodes = episodes;
        access = new InternalActorAccess(new InternalActorTokens(secret), authUrl);
    }

    @GetMapping("/tickets/{id}")
    ApiResponse<TicketDtos.View> ticket(
            @RequestHeader("Authorization") String authorization, @PathVariable long id) {
        var actor = access.verify(authorization, "ticket");
        try (var scope = InternalActorAccess.open(actor)) {
            tools.target(actor, service.require(id));
            return ApiResponse.success(service.detail(id));
        }
    }

    @GetMapping("/tickets/{id}/history")
    ApiResponse<List<TicketAuditMapper.History>> history(
            @RequestHeader("Authorization") String authorization, @PathVariable long id) {
        var actor = access.verify(authorization, "ticket");
        try (var scope = InternalActorAccess.open(actor)) {
            tools.target(actor, service.require(id));
            return ApiResponse.success(service.history(id));
        }
    }

    @GetMapping("/tickets/{id}/workspace-context")
    ApiResponse<TicketDtos.View> workspaceContext(
            @RequestHeader("Authorization") String authorization, @PathVariable long id) {
        var actor = access.verify(authorization, "ticket");
        try (var scope = InternalActorAccess.open(actor)) {
            // Discover the ticket's target only after its ordinary read permission is checked.
            // All execution endpoints still require an exact target and run-bound context.
            return ApiResponse.success(service.detail(id));
        }
    }

    @PostMapping("/tickets/{id}/{operation:ai-analyses|comments|work-records|transitions}")
    ApiResponse<JsonNode> write(
            @RequestHeader("Authorization") String authorization,
            @PathVariable long id,
            @PathVariable String operation,
            @Valid @RequestBody InternalTicketTools.Call call) {
        var actor = access.verify(authorization, "ticket");
        try (var scope = InternalActorAccess.open(actor)) {
            return ApiResponse.success(tools.write(id, operation, call, actor));
        }
    }

    @GetMapping("/alerts/{episodeId}")
    ApiResponse<AlertEpisodeMapper.Episode> alert(
            @RequestHeader("Authorization") String authorization, @PathVariable String episodeId) {
        var actor = access.verify(authorization, "ticket");
        try (var scope = InternalActorAccess.open(actor)) {
            var episode = episodes.lock(episodeId);
            if (episode == null || episode.ticketId() == null)
                throw new BusinessException(ErrorCode.NOT_FOUND, "告警期不存在或尚未关联工单");
            tools.target(actor, service.require(episode.ticketId()));
            return ApiResponse.success(episode);
        }
    }
}
