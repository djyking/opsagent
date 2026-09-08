package com.opsagent.rag;

/**
 * 封装供应商无关的系统提示词、用户提示词和最大输出限制。
 *
 * @author heyu
 * @since 2026/8/30
 */
public record LlmRequest(
        String systemPrompt, String userPrompt, int maxOutputTokens, int priorReservedTokens) {
    public LlmRequest(String systemPrompt, String userPrompt, int maxOutputTokens) {
        this(systemPrompt, userPrompt, maxOutputTokens, 0);
    }

    public LlmRequest {
        if (priorReservedTokens < 0 || priorReservedTokens > AssistantTokenBudget.LIMIT) {
            throw new IllegalArgumentException("本次问答的先前预留额度无效");
        }
    }

    LlmRequest withPriorReservedTokens(int tokens) {
        return new LlmRequest(systemPrompt, userPrompt, maxOutputTokens, tokens);
    }
}
