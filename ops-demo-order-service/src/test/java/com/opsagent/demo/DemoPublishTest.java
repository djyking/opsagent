package com.opsagent.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 发布确认读取真实的序列边界，不把 ACK、旧值或空值当成配置生效。
 * @author heyu
 * @since 2026/9/3
 */
class DemoPublishTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final DemoRuntime runtime = new DemoRuntime(null, json, new SimpleMeterRegistry(), "", "", false);
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger publishes = new AtomicInteger();

    @AfterEach
    void cleanup() throws Exception {
        runtime.close();
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    void initialNullReadWaitsForPublishedBaselineWithoutInvalidConfigRestart() throws Exception {
        var desired = DemoConfiguration.baseline("", "BASELINE");
        String content = json.writeValueAsString(desired);
        configure(index -> index < 2 ? null : content, 0);
        ReflectionTestUtils.invokeMethod(runtime, "publish", desired);
        assertThat(reads).hasValue(3);
        assertThat(publishes).hasValue(1);
        assertThat(runtime.snapshot()).containsEntry("configurationStatus", "APPLIED")
                .containsEntry("appliedRevision", desired.revision());
    }

    @Test
    void staleRevisionIsNeverAppliedBeforeTheExpectedFaultRevisionAppears() throws Exception {
        String initialRevision = runtime.snapshot().get("appliedRevision").toString();
        var stale = DemoConfiguration.fault(UUID.randomUUID().toString(), "SENTINEL_RULE_REGRESSION",
                Instant.now().plusSeconds(600));
        var desired = DemoConfiguration.fault(UUID.randomUUID().toString(), "NACOS_REDIS_CONFIG_DRIFT",
                Instant.now().plusSeconds(600));
        String oldContent = json.writeValueAsString(stale);
        String newContent = json.writeValueAsString(desired);
        configure(index -> {
            assertThat(runtime.snapshot()).containsEntry("appliedRevision", initialRevision)
                    .containsEntry("redisPort", 6379);
            assertThat(FlowRuleManager.getRules().get(0).getCount()).isEqualTo(5);
            return index < 2 ? oldContent : newContent;
        }, 0);
        ReflectionTestUtils.invokeMethod(runtime, "publish", desired);
        assertThat(reads).hasValue(3);
        assertThat(publishes).hasValue(1);
        assertThat(runtime.snapshot()).containsEntry("appliedRevision", desired.revision())
                .containsEntry("redisPort", 6380).containsEntry("configurationStatus", "APPLIED");
    }

    @Test
    void continuouslyUnconfirmedPublishKeepsOriginalConfigurationWithinTotalBudget() throws Exception {
        String initialRevision = runtime.snapshot().get("appliedRevision").toString();
        String stale = json.writeValueAsString(DemoConfiguration.baseline("", "BASELINE"));
        var desired = DemoConfiguration.fault(UUID.randomUUID().toString(), "NACOS_REDIS_CONFIG_DRIFT",
                Instant.now().plusSeconds(600));
        configure(index -> index % 2 == 0 ? null : stale, 1200);
        Instant started = Instant.now();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(runtime, "publish", desired))
                .isInstanceOf(IllegalStateException.class).hasMessage("CONFIG_CONFIRMATION_PENDING");
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofMillis(6900));
        assertThat(publishes).hasValue(1);
        assertThat(reads.get()).isBetween(1, 60);
        assertThat(runtime.snapshot()).containsEntry("appliedRevision", initialRevision)
                .containsEntry("redisPort", 6379).containsEntry("configurationStatus", "NACOS_CONFIRMATION_PENDING");
    }

    private void configure(IntFunction<String> content, long publishMillis) {
        ConfigService service = (ConfigService) Proxy.newProxyInstance(ConfigService.class.getClassLoader(),
                new Class<?>[] {ConfigService.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("publishConfig")) {
                        publishes.incrementAndGet();
                        if (publishMillis > 0) Thread.sleep(publishMillis);
                        return true;
                    }
                    if (method.getName().equals("getConfig")) {
                        assertThat((long) arguments[2]).isBetween(1L, 500L);
                        return content.apply(reads.getAndIncrement());
                    }
                    if (method.getName().equals("shutDown")) return null;
                    throw new AssertionError("Unexpected Nacos operation: " + method.getName());
                });
        ReflectionTestUtils.setField(runtime, "config", service);
    }
}
