package com.example.opsagent.ai.client;

import org.springframework.stereotype.Component;

/**
 * 在未接入真实模型时提供明确、无外部依赖的模拟回答。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Component
public class MockAiModelClient implements AiModelClient {

    @Override
    public String chat(String prompt) {
        return "当前使用模拟模型，已完成文档片段检索；配置真实 AiModelClient 后可生成正式回答。";
    }
}
