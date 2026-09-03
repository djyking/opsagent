package com.opsagent.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库文件目录和文本切片参数。
 *
 * @author heyu
 * @since 2026/9/2
 */
@ConfigurationProperties("ops.knowledge")
public class KnowledgeProperties {
    private String storageRoot = "./data/uploads";
    private int chunkSize = 2400;
    private int chunkOverlap = 200;

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String v) {
        storageRoot = v;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int v) {
        chunkSize = v;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int v) {
        chunkOverlap = v;
    }
}
