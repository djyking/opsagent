package com.opsagent.rag;

import org.springframework.stereotype.Component;

/**
 * 通过兼容协议调用 DeepSeek 模型。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Component
public class DeepSeekLlmClient extends ChatCompletionsLlmClient {
    DeepSeekLlmClient(AiProperties properties, AiHttpExecutor http) {
        super(properties, http);
    }

    @Override
    public String provider() {
        return "deepseek";
    }
}
