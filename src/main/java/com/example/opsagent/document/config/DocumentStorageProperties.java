package com.example.opsagent.document.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档本地存储目录和单文件大小限制配置。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-agent.document")
public class DocumentStorageProperties {

    private String storageRoot = "./data/uploads";

    private long maxFileSize = 10 * 1024 * 1024;
}
