package com.opsagent.rag;

/**
 * 统一三个大模型供应商的回答文本和用量信息。
 *
 * @author heyu
 * @since 2026/8/30
 */
public record LlmResult(
        String text,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        boolean generationComplete,
        String finishReason,
        int continuationCount,
        int budgetLimit,
        int budgetChargedTokens,
        boolean budgetUsageKnown,
        int requestAttempts) {
    public LlmResult(
            String text,
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            boolean generationComplete,
            String finishReason,
            int continuationCount) {
        this(
                text,
                provider,
                model,
                inputTokens,
                outputTokens,
                generationComplete,
                finishReason,
                continuationCount,
                0,
                0,
                false,
                0);
    }

    public LlmResult(
            String text, String provider, String model, int inputTokens, int outputTokens) {
        this(text, provider, model, inputTokens, outputTokens, true, "stop", 0);
    }

    LlmResult withBudget(AssistantTokenBudget budget) {
        return new LlmResult(
                text,
                provider,
                model,
                inputTokens,
                outputTokens,
                generationComplete,
                finishReason,
                continuationCount,
                AssistantTokenBudget.LIMIT,
                budget.charged(),
                budget.usageKnown(),
                budget.attempts());
    }
}
