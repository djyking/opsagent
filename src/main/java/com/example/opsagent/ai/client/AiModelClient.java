package com.example.opsagent.ai.client;

/**
 * 将问答编排与具体大模型 HTTP 协议隔离。
 *
 * @author heyu
 * @since 2026/8/16
 */
public interface AiModelClient {

    AiModelResponse chat(String systemPrompt, String userPrompt);
}
