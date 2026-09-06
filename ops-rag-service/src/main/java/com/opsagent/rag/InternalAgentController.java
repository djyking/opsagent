package com.opsagent.rag;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部 AI/RAG 入口，所有方法先验专用签名并向 Auth 复核当前身份，绝不信任公网 Actor Header。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class InternalAgentController {
    private final InternalAgentModelService models;
    private final InternalAgentSearchService search;
    private final InternalActorTokens tokens;
    private final InternalActorAccess access;

    InternalAgentController(
            InternalAgentModelService models,
            InternalAgentSearchService search,
            @Value("${OPS_AGENT_INTERNAL_SECRET:}") String secret,
            @Value("${OPS_AUTH_INTERNAL_URL:${OPS_AGENT_AUTH_URL:http://ops-auth-service:8101}}")
                    String authUrl) {
        this.models = models;
        this.search = search;
        tokens = new InternalActorTokens(secret);
        access = new InternalActorAccess(tokens, authUrl);
    }

    @GetMapping("/internal/ai/models")
    ApiResponse<InternalAgentDtos.Models> models(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        var actor = access.verify(authorization, "rag");
        try (var scope = InternalActorAccess.open(actor)) {
            return ApiResponse.success(models.models());
        }
    }

    @PostMapping("/internal/ai/models/{provider}/probe")
    ApiResponse<InternalAgentDtos.ModelCapability> probe(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String provider) {
        var actor = access.verify(authorization, "rag");
        try (var scope = InternalActorAccess.open(actor)) {
            return ApiResponse.success(models.probe(provider, actor));
        }
    }

    @PostMapping("/internal/ai/turns")
    ApiResponse<InternalAgentDtos.TurnResponse> turn(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody InternalAgentDtos.TurnRequest request) {
        var actor = access.verify(authorization, "rag");
        try (var scope = InternalActorAccess.open(actor)) {
            return ApiResponse.success(models.turn(request, actor));
        }
    }

    @PostMapping("/internal/rag/search")
    ApiResponse<InternalAgentDtos.SearchResponse> search(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody InternalAgentDtos.SearchRequest request) {
        var actor = access.verify(authorization, "rag");
        try (var scope = InternalActorAccess.open(actor)) {
            return ApiResponse.success(search.search(request, actor, tokens));
        }
    }
}
