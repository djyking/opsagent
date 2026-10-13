package com.opsagent.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理知识向量化、Embedding、Elasticsearch 文本索引和 Qdrant 参数。
 *
 * @author heyu
 * @since 2026/8/29
 */
@Component
@ConfigurationProperties(prefix = "ops.knowledge.vector")
public class VectorProperties {
    private boolean enabled;
    private String elasticsearchUrl = "http://localhost:9200";
    private String indexName = "ops_knowledge_chunk_v2";
    private String readAlias = "ops_knowledge_chunk_read";
    private String writeAlias = "ops_knowledge_chunk_write";
    private String analyzer = "smartcn";
    private String qdrantUrl = "http://localhost:6333";
    private String qdrantCollection = "ops_knowledge_vector_v1";
    private String qdrantAlias = "ops_knowledge_vector_read";
    private String qdrantApiKey = "";
    private int dimensions = 1536;
    private double minimumScore = 0.72D;
    private String embeddingBaseUrl = "https://api.openai.com/v1";
    private String embeddingApiKey = "";
    private String embeddingModel = "text-embedding-3-small";
    private int timeoutSeconds = 30;
    private int embeddingBatchSize = 32;
    private int embeddingBatchMaxTokens = 12000;
    private int bulkSize = 100;
    private int bm25TopK = 50;
    private int vectorTopK = 50;
    private int vectorCandidates = 100;
    private int rrfWindow = 50;
    private int rrfRankConstant = 60;
    private int hybridCandidates = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getElasticsearchUrl() {
        return elasticsearchUrl;
    }

    public void setElasticsearchUrl(String elasticsearchUrl) {
        this.elasticsearchUrl = elasticsearchUrl;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getReadAlias() {
        return readAlias;
    }

    public void setReadAlias(String readAlias) {
        this.readAlias = readAlias;
    }

    public String getWriteAlias() {
        return writeAlias;
    }

    public void setWriteAlias(String writeAlias) {
        this.writeAlias = writeAlias;
    }

    public String getAnalyzer() {
        return analyzer;
    }

    public void setAnalyzer(String analyzer) {
        this.analyzer = analyzer;
    }

    public String getQdrantUrl() {
        return qdrantUrl;
    }

    public void setQdrantUrl(String qdrantUrl) {
        this.qdrantUrl = qdrantUrl;
    }

    public String getQdrantCollection() {
        return qdrantCollection;
    }

    public void setQdrantCollection(String qdrantCollection) {
        this.qdrantCollection = qdrantCollection;
    }

    public String getQdrantAlias() {
        return qdrantAlias;
    }

    public void setQdrantAlias(String qdrantAlias) {
        this.qdrantAlias = qdrantAlias;
    }

    public String getQdrantApiKey() {
        return qdrantApiKey;
    }

    public void setQdrantApiKey(String qdrantApiKey) {
        this.qdrantApiKey = qdrantApiKey;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public double getMinimumScore() {
        return minimumScore;
    }

    public void setMinimumScore(double minimumScore) {
        this.minimumScore = minimumScore;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public void setEmbeddingApiKey(String embeddingApiKey) {
        this.embeddingApiKey = embeddingApiKey;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public int getEmbeddingBatchMaxTokens() {
        return embeddingBatchMaxTokens;
    }

    public void setEmbeddingBatchMaxTokens(int embeddingBatchMaxTokens) {
        this.embeddingBatchMaxTokens = embeddingBatchMaxTokens;
    }

    public int getBulkSize() {
        return bulkSize;
    }

    public void setBulkSize(int bulkSize) {
        this.bulkSize = bulkSize;
    }

    public int getBm25TopK() {
        return bm25TopK;
    }

    public void setBm25TopK(int bm25TopK) {
        this.bm25TopK = bm25TopK;
    }

    public int getVectorTopK() {
        return vectorTopK;
    }

    public void setVectorTopK(int vectorTopK) {
        this.vectorTopK = vectorTopK;
    }

    public int getVectorCandidates() {
        return vectorCandidates;
    }

    public void setVectorCandidates(int vectorCandidates) {
        this.vectorCandidates = vectorCandidates;
    }

    public int getRrfWindow() {
        return rrfWindow;
    }

    public void setRrfWindow(int rrfWindow) {
        this.rrfWindow = rrfWindow;
    }

    public int getRrfRankConstant() {
        return rrfRankConstant;
    }

    public void setRrfRankConstant(int rrfRankConstant) {
        this.rrfRankConstant = rrfRankConstant;
    }

    public int getHybridCandidates() {
        return hybridCandidates;
    }

    public void setHybridCandidates(int hybridCandidates) {
        this.hybridCandidates = hybridCandidates;
    }

    boolean configured() {
        return enabled && embeddingApiKey != null && !embeddingApiKey.isBlank();
    }
}
