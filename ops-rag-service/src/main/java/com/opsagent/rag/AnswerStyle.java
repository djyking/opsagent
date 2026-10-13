package com.opsagent.rag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * User-selected answer detail, without changing request token budgets.
 *
 * @author heyu
 * @since 2026/9/3
 */
enum AnswerStyle {
    CONCISE,
    DETAILED;

    @JsonCreator
    static AnswerStyle parse(String value) {
        if (value == null || value.isBlank()) return CONCISE;
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    @JsonValue
    String value() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    static AnswerStyle orDefault(AnswerStyle value) {
        return value == null ? CONCISE : value;
    }

    LlmRequest apply(LlmRequest request) {
        if (request == null) return null;
        String instructions =
                this == DETAILED
                        ? "\n用户选择深入分析：先给主结论，再展开关键证据、推理依据、限制和可执行的下一步，避免重复。"
                        : "\n用户选择精简回答：默认先给简短主结论，仅保留最关键的证据与下一步，通常控制在 3—6 个要点，不重复背景、全量指标或长篇知识介绍。";
        instructions +=
                "服务诊断无论详略都必须区分已知事实、合理推断与证据缺口，保留必要引用和影响判断。"
                        + "用户在当前问题中明确要求的长度或格式优先；不得为精简而省略必要的不确定性或编造结论。";
        return new LlmRequest(
                request.systemPrompt() + instructions,
                request.userPrompt(),
                request.maxOutputTokens(),
                request.priorReservedTokens());
    }
}
