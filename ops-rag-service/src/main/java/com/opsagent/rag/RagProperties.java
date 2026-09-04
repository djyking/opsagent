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
    private boolean rerankEnabled;
    private String rerankBaseUrl = "http://localhost:8010";
    private int rerankTimeoutSeconds = 3;
    private int rerankTopN = 6;
    private int retrievalCandidates = 30;
    private int maxContextTokens = 6000;
    private int maxChunksPerDocument = 3;
    private int neighborWindow = 1;

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

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public String getRerankBaseUrl() {
        return rerankBaseUrl;
    }

    public void setRerankBaseUrl(String rerankBaseUrl) {
        this.rerankBaseUrl = rerankBaseUrl;
    }

    public int getRerankTimeoutSeconds() {
        return rerankTimeoutSeconds;
    }

    public void setRerankTimeoutSeconds(int rerankTimeoutSeconds) {
        this.rerankTimeoutSeconds = rerankTimeoutSeconds;
    }

    public int getRerankTopN() {
        return rerankTopN;
    }

    public void setRerankTopN(int rerankTopN) {
        this.rerankTopN = rerankTopN;
    }

    public int getRetrievalCandidates() {
        return retrievalCandidates;
    }

    public void setRetrievalCandidates(int retrievalCandidates) {
        this.retrievalCandidates = retrievalCandidates;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public int getMaxChunksPerDocument() {
        return maxChunksPerDocument;
    }

    public void setMaxChunksPerDocument(int maxChunksPerDocument) {
        this.maxChunksPerDocument = maxChunksPerDocument;
    }

    public int getNeighborWindow() {
        return neighborWindow;
    }

    public void setNeighborWindow(int neighborWindow) {
        this.neighborWindow = neighborWindow;
    }

    int limit(Integer requested) {
        int value = requested == null ? topK : requested;
        return Math.max(1, Math.min(value, maximumTopK));
    }
}
