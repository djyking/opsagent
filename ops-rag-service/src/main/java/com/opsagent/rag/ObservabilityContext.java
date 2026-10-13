package com.opsagent.rag;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Set;

/**
 * 客户端只提交观测范围和证据引用，不能提交待模型信任的运行事实。
 *
 * @author heyu
 * @since 2026/9/3
 */
public record ObservabilityContext(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,64}") String service,
        @NotBlank @Pattern(regexp = "PROD|DEMO") String environment,
        @NotBlank @Pattern(regexp = "5m|15m|30m|1h|6h") String timeRange,
        @Pattern(
                        regexp =
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                String evidenceBundleId) {
    public ObservabilityContext {
        if (service == null
                || !service.matches("[A-Za-z0-9_.:-]{1,64}")
                || environment == null
                || !Set.of("PROD", "DEMO").contains(environment)
                || timeRange == null
                || !Set.of("5m", "15m", "30m", "1h", "6h").contains(timeRange)
                || evidenceBundleId != null
                        && !evidenceBundleId.matches(
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new BusinessException(ErrorCode.VALIDATION, "观测范围或证据包引用格式无效");
        }
    }

    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw new BusinessException(ErrorCode.VALIDATION, "观测上下文只接受服务、环境、窗口和证据包引用");
    }
}
