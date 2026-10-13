package com.opsagent.knowledge;

import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 为单 Redis 演示环境收敛 Redisson 线程和空闲连接，沿用原有地址、认证及锁机制。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoRedisConfiguration {
    @Bean
    RedissonAutoConfigurationCustomizer demoRedisBudget(
            @Value("${ops.demo.redis.worker-threads:2}") int workerThreads,
            @Value("${ops.demo.redis.netty-threads:2}") int nettyThreads,
            @Value("${ops.demo.redis.max-connections:8}") int maxConnections) {
        if (workerThreads < 1 || nettyThreads < 1 || maxConnections < 1) {
            throw new IllegalArgumentException("演示环境 Redis 资源预算必须为正数");
        }
        return config -> {
            config.setThreads(workerThreads).setNettyThreads(nettyThreads);
            if (config.isSingleConfig()) {
                config.useSingleServer()
                        .setConnectionMinimumIdleSize(1)
                        .setConnectionPoolSize(maxConnections)
                        .setSubscriptionConnectionMinimumIdleSize(1)
                        .setSubscriptionConnectionPoolSize(2);
            }
        };
    }
}
