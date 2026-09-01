package com.example.opsagent.ai.config;

import java.time.Duration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配置简单检索范围和 OpenAI 兼容模型调用参数。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-agent.ai")
public class AiProperties {

    private int topK = 3;

    private int candidateLimit = 200;

    private boolean enabled;

    private String baseUrl = "https://api.openai.com/v1";

    private String apiKey;

    private String model = "gpt-4.1-mini";

    private Duration connectTimeout = Duration.ofSeconds(10);

    private Duration readTimeout = Duration.ofSeconds(60);
}
