package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A read-only identity lookup for the authenticated Alertmanager ingestion service.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class AlertTargetController {
    private final AlertTargetResolver resolver;
    private final InternalActorTokens tokens;

    AlertTargetController(
            AlertTargetResolver resolver,
            @Value("${ops.agent.internal-secret:${OPS_AGENT_INTERNAL_SECRET:}}") String secret) {
        this.resolver = resolver;
        this.tokens = new InternalActorTokens(secret);
    }

    @PostMapping("/internal/platform/alert-targets/resolve")
    ApiResponse<AlertTargetResolver.Resolution> resolve(
            @RequestHeader(value = "Authorization", required = false) String header,
            @RequestBody JsonNode labels) {
        var actor = tokens.verify(header, "platform");
        if (actor.userId() != Long.MAX_VALUE
                || !"alertmanager-linker".equals(actor.username())
                || !actor.roles().equals(List.of("SYSTEM"))
                || !"alert-target-resolution".equals(actor.targetCode())
                || !actor.runId().matches("[a-f0-9]{64}"))
            throw new BusinessException(ErrorCode.FORBIDDEN, "ALERT_TARGET_RESOLVER_FORBIDDEN");
        return ApiResponse.success(resolver.resolve(labels));
    }
}
