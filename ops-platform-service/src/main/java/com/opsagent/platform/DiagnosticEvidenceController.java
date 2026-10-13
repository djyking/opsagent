package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 公开会话与内部Agent/RAG使用同一证据服务；内部请求逐次重鉴权并绑定目标。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class DiagnosticEvidenceController {
    private final DiagnosticEvidenceService service;
    private final InternalActorAccess access;

    DiagnosticEvidenceController(
            DiagnosticEvidenceService service,
            @Value("${ops.agent.internal-secret:}") String secret,
            @Value("${ops.agent.auth-url:http://localhost:8101}") String authUrl) {
        this.service = service;
        this.access = new InternalActorAccess(new InternalActorTokens(secret), authUrl);
    }

    @PostMapping({
        "/api/platform/observability/evidence",
        "/api/platform/observability/evidence/resolve"
    })
    ResponseEntity<ApiResponse<Map<String, Object>>> resolve(
            @Valid @RequestBody DiagnosticEvidenceDtos.Reference reference) {
        return response(service.resolve(reference, null));
    }

    @PostMapping("/internal/platform/observability/evidence")
    ResponseEntity<ApiResponse<Map<String, Object>>> internal(
            @RequestHeader(value = "Authorization", required = false) String header,
            @Valid @RequestBody DiagnosticEvidenceDtos.Reference reference) {
        var actor = access.verify(header, "platform");
        if (!actor.targetCode().equals(reference.service())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "证据服务与内部运行绑定目标不匹配");
        }
        try (var ignored = InternalActorAccess.open(actor)) {
            return response(service.resolve(reference, actor));
        }
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> response(Map<String, Object> bundle) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(bundle));
    }
}
