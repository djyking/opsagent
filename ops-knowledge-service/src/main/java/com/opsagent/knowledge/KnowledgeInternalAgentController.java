package com.opsagent.knowledge;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 的真实权限检索桥接，沿用知识服务原有可见性过滤和安全降级。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class KnowledgeInternalAgentController {
    private final KnowledgeService service;
    private final InternalActorAccess access;

    KnowledgeInternalAgentController(
            KnowledgeService service,
            @Value("${OPS_AGENT_INTERNAL_SECRET:}") String secret,
            @Value("${OPS_AUTH_INTERNAL_URL:${OPS_AGENT_AUTH_URL:http://ops-auth-service:8101}}")
                    String authUrl) {
        this.service = service;
        access = new InternalActorAccess(new InternalActorTokens(secret), authUrl);
    }

    @PostMapping("/internal/agent/search")
    ApiResponse<List<Map<String, Object>>> search(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody SearchRequest request) {
        var actor = access.verify(authorization, "knowledge");
        try (var scope = InternalActorAccess.open(actor)) {
            return ApiResponse.success(service.search(request.query(), request.topK()));
        }
    }

    /**
     * 全局已授权检索参数，不接受调用方自报管理员或可见文档列表。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record SearchRequest(@NotBlank @Size(max = 2000) String query, @Min(1) @Max(30) int topK) {}
}
