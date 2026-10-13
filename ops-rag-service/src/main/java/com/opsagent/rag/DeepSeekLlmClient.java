package com.opsagent.rag;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通过兼容协议调用 DeepSeek 模型。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Component
public class DeepSeekLlmClient extends ChatCompletionsLlmClient {
    DeepSeekLlmClient(
            AiProperties properties,
            AiHttpExecutor http,
            AiStreamHttpExecutor streamHttp) {
        super(properties, http, streamHttp);
    }

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    protected Map<String, Object> additionalBody() {
        return Map.of("thinking", Map.of("type", "disabled"));
    }
}
