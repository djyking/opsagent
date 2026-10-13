package com.opsagent.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识库与文档处理服务启动入口。
 *
 * @author heyu
 * @since 2026/8/22
 */
@SpringBootApplication
@EnableConfigurationProperties(KnowledgeProperties.class)
@EnableScheduling
public class OpsKnowledgeApplication {
    public static void main(String[] a) {
        SpringApplication.run(OpsKnowledgeApplication.class, a);
    }
}
