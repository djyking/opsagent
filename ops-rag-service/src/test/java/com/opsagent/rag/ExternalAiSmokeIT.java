package com.opsagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提供默认 Maven 测试不会执行的三供应商真实 API 冒烟测试。
 *
 * @author heyu
 * @since 2026/9/2
 */
@Tag("external-ai")
class ExternalAiSmokeIT {
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void openAiShouldAnswerOk() {
        AiProperties properties = properties(
                "openai",
                "https://api.openai.com/v1",
                "OPENAI_API_KEY",
                "gpt-5.6-luna");
        assertThat(new OpenAiLlmClient(
                                properties,
                                new AiHttpExecutor(),
                                new AiStreamHttpExecutor(new ObjectMapper()))
                        .generate(request()).text())
                .isEqualToIgnoringCase("OK");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    void deepSeekShouldAnswerOk() {
        AiProperties properties = properties(
                "deepseek",
                "https://api.deepseek.com",
                "DEEPSEEK_API_KEY",
                "deepseek-v4-flash");
        assertThat(new DeepSeekLlmClient(
                                properties,
                                new AiHttpExecutor(),
                                new AiStreamHttpExecutor(new ObjectMapper()))
                        .generate(request()).text())
                .isEqualToIgnoringCase("OK");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MOONSHOT_API_KEY", matches = ".+")
    void kimiShouldAnswerOk() {
        AiProperties properties = properties(
                "kimi",
                "https://api.moonshot.cn/v1",
                "MOONSHOT_API_KEY",
                "kimi-k2.6");
        assertThat(new KimiLlmClient(
                                properties,
                                new AiHttpExecutor(),
                                new AiStreamHttpExecutor(new ObjectMapper()))
                        .generate(request()).text())
                .isEqualToIgnoringCase("OK");
    }

    private AiProperties properties(
            String provider, String baseUrl, String keyVariable, String model) {
        AiProperties.ProviderSettings settings = new AiProperties.ProviderSettings();
        settings.setBaseUrl(baseUrl);
        settings.setApiKey(System.getenv(keyVariable));
        settings.setModel(model);
        Map<String, AiProperties.ProviderSettings> providers = new LinkedHashMap<>();
        providers.put(provider, settings);
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setProvider(provider);
        properties.setProviders(providers);
        return properties;
    }

    private LlmRequest request() {
        return new LlmRequest(
                "你是连通性检查程序。严格按照用户要求输出。",
                "你好，请只回答 OK",
                64);
    }
}
