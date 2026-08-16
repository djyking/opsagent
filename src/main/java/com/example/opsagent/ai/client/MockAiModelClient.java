package com.example.opsagent.ai.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 在未启用外部模型时返回明确的本地占位结果，不伪造文档结论。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Component
@ConditionalOnProperty(prefix = "ops-agent.ai", name = "enabled", havingValue = "false", matchIfMissing = true)
public class MockAiModelClient implements AiModelClient {

    @Override
    public AiModelResponse chat(String systemPrompt, String userPrompt) {
        return new AiModelResponse("当前未启用外部模型，无法从当前文档中生成正式回答。", "local-placeholder", null, null);
    }
}
