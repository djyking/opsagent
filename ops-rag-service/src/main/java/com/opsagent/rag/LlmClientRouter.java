package com.opsagent.rag;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据配置选择模型客户端，禁止业务层感知供应商协议差异。
 *
 * @author heyu
 * @since 2026/9/1
 */
@Component
public class LlmClientRouter {
    private final AiProperties properties;
    private final Map<String, LlmClient> clients;

    LlmClientRouter(AiProperties properties, List<LlmClient> clients) {
        this.properties = properties;
        this.clients = new LinkedHashMap<>();
        clients.forEach(client -> this.clients.put(client.provider(), client));
    }

    LlmClient selected() {
        return byName(properties.getProvider());
    }

    LlmClient byName(String provider) {
        LlmClient client = clients.get(provider == null ? "" : provider.toLowerCase());
        if (client == null) {
            throw new IllegalArgumentException("不支持的 AI Provider：" + provider);
        }
        return client;
    }

    List<LlmClient> all() {
        return List.copyOf(clients.values());
    }
}
