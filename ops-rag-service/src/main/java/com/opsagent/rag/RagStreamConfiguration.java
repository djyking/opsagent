package com.opsagent.rag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 配置独立的 RAG SSE 工作线程池，隔离长连接与 Servlet 请求线程。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Configuration
public class RagStreamConfiguration {
    @Bean(name = "ragStreamExecutor")
    Executor ragStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("rag-sse-");
        executor.initialize();
        return executor;
    }
}
