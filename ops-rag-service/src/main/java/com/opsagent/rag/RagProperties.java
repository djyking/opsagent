package com.opsagent.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理检索数量和上下文预算等 RAG 运行边界。
 *
 * @author heyu
 * @since 2026/8/30
 */
@Component
@ConfigurationProperties(prefix = "ops.rag")
public class RagProperties {
    private int topK = 5;
    private int maximumTopK = 20;
    private int contextCharacterBudget = 16000;
    private int requestsPerMinute = 20;

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMaximumTopK() {
        return maximumTopK;
    }

    public void setMaximumTopK(int maximumTopK) {
        this.maximumTopK = maximumTopK;
    }

    public int getContextCharacterBudget() {
        return contextCharacterBudget;
    }

    public void setContextCharacterBudget(int contextCharacterBudget) {
        this.contextCharacterBudget = contextCharacterBudget;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    int limit(Integer requested) {
        int value = requested == null ? topK : requested;
        return Math.max(1, Math.min(value, maximumTopK));
    }
}
