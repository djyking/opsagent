package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    static final List<String> SUPPORTED = List.of("deepseek", "openai", "kimi");
    private boolean enabled;
    private String provider = "deepseek";
    private int timeoutSeconds = 45;
    private int maximumAttempts = 3;
    private int maxOutputTokens = 4096;
    private int maximumContinuations = 2;
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

    public int getMaximumContinuations() {
        return Math.max(0, Math.min(maximumContinuations, 3));
    }

    public void setMaximumContinuations(int maximumContinuations) {
        this.maximumContinuations = maximumContinuations;
    }

    long streamTimeoutMillis() {
        long perAttempt = Math.max(3, Math.min(timeoutSeconds, 120));
        int attempts = Math.max(1, Math.min(maximumAttempts, 3));
        return ((perAttempt * attempts + 2) * (getMaximumContinuations() + 1) + 30) * 1000L;
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

    String resolveProvider(String requested) {
        String selected = requested == null || requested.isBlank() ? provider : requested;
        selected = selected == null ? "" : selected.trim().toLowerCase(Locale.ROOT);
        if (requested != null && !requested.isBlank()) {
            if (!SUPPORTED.contains(selected)) {
                throw new BusinessException(ErrorCode.VALIDATION, "不支持该模型供应商，请从模型列表选择");
            }
            if (!enabled || !settings(selected).selectable()) {
                throw new BusinessException(ErrorCode.CONFLICT, "所选模型当前未启用或未配置完成，请重新选择可用模型");
            }
        }
        return selected;
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

        boolean selectable() {
            if (!configured()) return false;
            try {
                URI uri = URI.create(baseUrl == null ? "" : baseUrl);
                return uri.getHost() != null
                        && uri.getUserInfo() == null
                        && ("https".equalsIgnoreCase(uri.getScheme())
                                || "http".equalsIgnoreCase(uri.getScheme()));
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }
}
