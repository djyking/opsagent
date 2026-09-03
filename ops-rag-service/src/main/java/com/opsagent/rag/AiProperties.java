package com.opsagent.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理大模型总开关、默认供应商以及各供应商的安全运行参数。
 *
 * @author heyu
 * @since 2026/8/29
 */
@Component
@ConfigurationProperties(prefix = "ops.ai")
public class AiProperties {
    private boolean enabled;
    private String provider = "deepseek";
    private int timeoutSeconds = 45;
    private int maximumAttempts = 3;
    private int maxOutputTokens = 1200;
    private Map<String, ProviderSettings> providers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaximumAttempts() {
        return maximumAttempts;
    }

    public void setMaximumAttempts(int maximumAttempts) {
        this.maximumAttempts = maximumAttempts;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Map<String, ProviderSettings> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderSettings> providers) {
        this.providers = providers;
    }

    ProviderSettings settings(String name) {
        ProviderSettings settings = providers.get(name);
        return settings == null ? new ProviderSettings() : settings;
    }

    /**
     * 描述单个模型供应商的地址、密钥、模型和协议风格。
     *
     * @author heyu
     * @since 2026/8/29
     */
    public static class ProviderSettings {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private String apiStyle = "chat-completions";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiStyle() {
            return apiStyle;
        }

        public void setApiStyle(String apiStyle) {
            this.apiStyle = apiStyle;
        }

        boolean configured() {
            return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
        }
    }
}
