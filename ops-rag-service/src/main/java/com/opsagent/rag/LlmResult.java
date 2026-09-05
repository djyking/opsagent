package com.opsagent.rag;

/**
 * 统一三个大模型供应商的回答文本和用量信息。
 *
 * @author heyu
 * @since 2026/8/30
 */
public record LlmResult(
        String text, String provider, String model, int inputTokens, int outputTokens,
        boolean generationComplete, String finishReason, int continuationCount) {
    public LlmResult(String text, String provider, String model, int inputTokens, int outputTokens) {
        this(text, provider, model, inputTokens, outputTokens, true, "stop", 0);
    }
}
