package com.opsagent.rag;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通过兼容协议调用 Moonshot Kimi 模型。
 *
 * @author heyu
 * @since 2026/9/1
 */
@Component
public class KimiLlmClient extends ChatCompletionsLlmClient {
    KimiLlmClient(AiProperties properties, AiHttpExecutor http) {
        super(properties, http);
    }

    @Override
    public String provider() {
        return "kimi";
    }

    @Override
    protected Map<String, Object> additionalBody() {
        return Map.of("thinking", Map.of("type", "disabled"));
    }
}
