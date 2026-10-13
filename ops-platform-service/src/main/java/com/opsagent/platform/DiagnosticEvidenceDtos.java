package com.opsagent.platform;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 客户端只能引用服务和时间窗，不能提交监控事实或诊断答案。
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class DiagnosticEvidenceDtos {
    private DiagnosticEvidenceDtos() {}

    /**
     * @author heyu
     */
    public record Reference(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String service,
            @NotBlank @Pattern(regexp = "PROD|DEMO") String environment,
            @NotBlank @Pattern(regexp = "5m|15m|30m|1h|6h") String timeRange,
            @Pattern(regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    String evidenceBundleId,
            @Positive Long ticketId) {
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw new BusinessException(ErrorCode.VALIDATION, "证据请求仅接受引用字段，不接受客户端事实");
        }
    }
}
