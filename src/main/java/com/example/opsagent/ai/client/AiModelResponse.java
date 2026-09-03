package com.example.opsagent.ai.client;

/**
 * 承载模型回答、实际模型名与 Token 用量。
 *
 * @author heyu
 * @since 2026/8/16
 */
public record AiModelResponse(
        String answer, String modelName, Integer promptTokens, Integer completionTokens) {}
