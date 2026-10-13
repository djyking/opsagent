package com.opsagent.knowledge;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证演示配置按 profile 隔离，并且资源调整不会覆盖 Redis 连接身份。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DemoRedisConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DemoRedisConfiguration.class);

    @Test
    void shouldNotApplyDemoBudgetInNormalProfile() {
        runner.run(context -> assertThat(context).doesNotHaveBean(RedissonAutoConfigurationCustomizer.class));
    }

    @Test
    void shouldPreserveConnectionIdentityAndAllowThreadOverride() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"))
                .withPropertyValues("ops.demo.redis.worker-threads=3")
                .run(context -> {
                    Config config = new Config();
                    var server = config.useSingleServer()
                            .setAddress("redis://example.invalid:6379")
                            .setPassword("test-only")
                            .setDatabase(3);
                    context.getBean(RedissonAutoConfigurationCustomizer.class).customize(config);

                    assertThat(config.getThreads()).isEqualTo(3);
                    assertThat(config.getNettyThreads()).isEqualTo(2);
                    assertThat(server.getConnectionMinimumIdleSize()).isEqualTo(1);
                    assertThat(server.getConnectionPoolSize()).isEqualTo(8);
                    assertThat(server.getAddress()).isEqualTo("redis://example.invalid:6379");
                    assertThat(server.getPassword()).isEqualTo("test-only");
                    assertThat(server.getDatabase()).isEqualTo(3);
                });
    }
}
