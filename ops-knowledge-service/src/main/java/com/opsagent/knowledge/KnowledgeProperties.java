package com.opsagent.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库文件目录和文本切片参数。
 *
 * @author heyu
 * @since 2026/8/18
 */
@ConfigurationProperties("ops.knowledge")
public class KnowledgeProperties {
    private String storageRoot = "./data/uploads";
    private long maxFileSizeBytes = 10L * 1024 * 1024;
    private Chunk chunk = new Chunk();

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String v) {
        storageRoot = v;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public Chunk getChunk() {
        return chunk;
    }

    public void setChunk(Chunk chunk) {
        this.chunk = chunk;
    }

    /**
     * 管理基于 Token 的结构化切片阈值和策略版本。
     *
     * @author heyu
     * @since 2026/9/3
     */
    public static class Chunk {
        private int targetTokens = 500;
        private int maxTokens = 800;
        private int minTokens = 100;
        private int overlapTokens = 80;
        private String strategyVersion = "structure-v1";

        public int getTargetTokens() {
            return targetTokens;
        }

        public void setTargetTokens(int targetTokens) {
            this.targetTokens = targetTokens;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getMinTokens() {
            return minTokens;
        }

        public void setMinTokens(int minTokens) {
            this.minTokens = minTokens;
        }

        public int getOverlapTokens() {
            return overlapTokens;
        }

        public void setOverlapTokens(int overlapTokens) {
            this.overlapTokens = overlapTokens;
        }

        public String getStrategyVersion() {
            return strategyVersion;
        }

        public void setStrategyVersion(String strategyVersion) {
            this.strategyVersion = strategyVersion;
        }
    }
}
