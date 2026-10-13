package com.opsagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证配置名称能够稳定路由到对应的大模型适配器。
 *
 * @author heyu
 * @since 2026/9/2
 */
class LlmClientRouterTest {
    @Test
    void shouldRouteAllSupportedProviders() {
        AiProperties properties = new AiProperties();
        LlmClient openai = client("openai");
        LlmClient deepseek = client("deepseek");
        LlmClient kimi = client("kimi");
        LlmClientRouter router = new LlmClientRouter(
                properties, List.of(openai, deepseek, kimi));

        assertThat(router.byName("openai")).isSameAs(openai);
        assertThat(router.byName("deepseek")).isSameAs(deepseek);
        assertThat(router.byName("kimi")).isSameAs(kimi);
    }

    private LlmClient client(String provider) {
        LlmClient client = mock(LlmClient.class);
        when(client.provider()).thenReturn(provider);
        return client;
    }
}
