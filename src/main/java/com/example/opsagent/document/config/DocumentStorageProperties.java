package com.example.opsagent.document.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配置文档本地存储、单文件限制和文本切片参数。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-agent.document")
public class DocumentStorageProperties {

    private String storageRoot = "./data/uploads";

    private long maxFileSize = 50L * 1024 * 1024;

    private int chunkSize = 2400;

    private int chunkOverlap = 200;
}
