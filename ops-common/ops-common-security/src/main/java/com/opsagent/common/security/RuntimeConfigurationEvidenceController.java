package com.opsagent.common.security;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ConfigurationExecutionEvidence;
import com.opsagent.common.core.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loopback HMAC endpoint available while the separate authentication service is restarting.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
public class RuntimeConfigurationEvidenceController {
    private final RuntimeConfigurationEvidence evidence;

    public RuntimeConfigurationEvidenceController(RuntimeConfigurationEvidence evidence) {
        this.evidence = evidence;
    }

    @GetMapping(ConfigurationExecutionEvidence.PATH)
    public ResponseEntity<ApiResponse<ConfigurationExecutionEvidence.Snapshot>> read(
            HttpServletRequest request) {
        if (!evidence.authorized(
                request.getRemoteAddr(),
                request.getHeader("X-Ops-Executor-Time"),
                request.getHeader("X-Ops-Executor-Nonce"),
                request.getHeader("X-Ops-Executor-Signature")))
            throw new BusinessException(ErrorCode.FORBIDDEN, "执行器证据读取身份不合法");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(evidence.snapshot()));
    }
}
