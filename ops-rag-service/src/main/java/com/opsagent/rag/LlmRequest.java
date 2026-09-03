package com.opsagent.rag;

/**
 * 封装供应商无关的系统提示词、用户提示词和最大输出限制。
 *
 * @author heyu
 * @since 2026/8/30
 */
public record LlmRequest(String systemPrompt, String userPrompt, int maxOutputTokens) {}
