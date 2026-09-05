package com.opsagent.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 配置独立的 RAG SSE 工作线程池，隔离长连接与 Servlet 请求线程。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Configuration
public class RagStreamConfiguration {
    @Bean(name = "ragStreamExecutor")
    ThreadPoolTaskExecutor ragStreamExecutor(
            @Value("${ops.rag.stream.core-pool-size:4}") int corePoolSize,
            @Value("${ops.rag.stream.max-pool-size:16}") int maxPoolSize,
            @Value("${ops.rag.stream.queue-capacity:100}") int queueCapacity,
            @Value("${ops.rag.stream.keep-alive-seconds:60}") int keepAliveSeconds,
            @Value("${ops.rag.stream.allow-core-thread-timeout:false}") boolean allowCoreThreadTimeout) {
        if (corePoolSize < 1 || maxPoolSize < corePoolSize || queueCapacity < 0 || keepAliveSeconds < 1) {
            throw new IllegalArgumentException("RAG SSE 线程池参数无效");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setAllowCoreThreadTimeOut(allowCoreThreadTimeout);
        executor.setThreadNamePrefix("rag-sse-");
        return executor;
    }
}
