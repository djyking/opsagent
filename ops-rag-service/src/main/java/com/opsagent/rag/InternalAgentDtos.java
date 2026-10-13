package com.opsagent.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 供应商无关的单步决策和安全检索合同；工具消息采用原生 Chat Completions 形状。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class InternalAgentDtos {
    private InternalAgentDtos() {}

    /**
     * 单个持久模型决策；仅明确 HTTP 临时拒绝最多重试一次，共享预算与总期限，不执行工具。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record TurnRequest(
            @NotBlank @Size(max = 160) String callId,
            @NotBlank @Size(max = 20) String provider,
            @NotBlank @Size(max = 128) String model,
            @NotEmpty @Size(max = 64) List<Map<String, Object>> messages,
            @NotNull @Size(max = 20) List<Map<String, Object>> tools,
            @Min(1) @Max(8192) int maxOutputTokens,
            @Min(1) @Max(100000) int remainingTokens) {}

    /**
     * 工具调用完成一个模型步，尚未代表整个 Agent 运行完成；缺失用量不是零。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record TurnResponse(
            String outcome,
            Map<String, Object> assistantMessage,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            boolean usageKnown,
            String provider,
            String model,
            String finishReason,
            long latencyMs,
            Integer budgetTokens,
            int attempts) {
        TurnResponse(
                String outcome,
                Map<String, Object> assistantMessage,
                Integer inputTokens,
                Integer outputTokens,
                Integer totalTokens,
                boolean usageKnown,
                String provider,
                String model,
                String finishReason,
                long latencyMs) {
            this(
                    outcome,
                    assistantMessage,
                    inputTokens,
                    outputTokens,
                    totalTokens,
                    usageKnown,
                    provider,
                    model,
                    finishReason,
                    latencyMs,
                    null,
                    1);
        }
    }

    /**
     * 已配置与已验证工具能力分别展示；不包含密钥和请求地址。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record ModelCapability(
            String provider,
            String model,
            boolean configured,
            boolean toolCalling,
            String verificationStatus,
            String verifiedAt,
            String configurationStatus) {
        ModelCapability(
                String provider,
                String model,
                boolean configured,
                boolean toolCalling,
                String verificationStatus,
                String verifiedAt) {
            this(provider, model, configured, toolCalling, verificationStatus, verifiedAt, null);
        }
    }

    /**
     * 可用模型清单。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Models(List<ModelCapability> models) {}

    /**
     * 有界知识检索，不接受可扩大数据权限的标记。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record SearchRequest(@NotBlank @Size(max = 2000) String query, @Min(1) @Max(20) int topK) {}

    /**
     * 经过权限过滤、重排与上下文预算处理的证据和来源。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record SearchResponse(String evidence, List<RagService.Source> citations) {}
}
