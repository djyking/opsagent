package com.opsagent.demo;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证真实 Sentinel 拦截、Redis调用错误传播及独立TTL保护，不伪造业务成功。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DemoRuntimeTest {
    private final DemoRedis redis = mock(DemoRedis.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final DemoRuntime runtime = new DemoRuntime(redis, json, new SimpleMeterRegistry(), "", "", false);

    @AfterEach
    void cleanup() throws Exception {
        runtime.close();
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    void nacosConfigurationChangesRealRedisPortAndPreservesFailureEvidence() throws Exception {
        when(redis.catalog(6380)).thenThrow(new IllegalStateException("connection refused"));
        runtime.accept(json.writeValueAsString(DemoConfiguration.fault(UUID.randomUUID().toString(),
                "NACOS_REDIS_CONFIG_DRIFT", Instant.now().plusSeconds(600))));
        var result = runtime.preview();
        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(result.reasonCode()).isEqualTo("REDIS_CONNECT_FAILED");
        verify(redis).catalog(6380);
    }

    @Test
    void sentinelRuleActuallyBlocksBeforeRedisIsCalled() throws Exception {
        runtime.accept(json.writeValueAsString(DemoConfiguration.fault(UUID.randomUUID().toString(),
                "SENTINEL_RULE_REGRESSION", Instant.now().plusSeconds(600))));
        assertThat(runtime.preview().httpStatus()).isEqualTo(429);
        assertThat(FlowRuleManager.getRules()).hasSize(1);
        assertThat(FlowRuleManager.getRules().get(0).getCount()).isZero();
        verify(redis, never()).catalog(6379);
    }

    @Test
    void targetTtlRestoresBaselineWithoutAgentOrNacosBeingAvailable() throws Exception {
        var expired = new DemoConfiguration(UUID.randomUUID().toString(), "NACOS_REDIS_CONFIG_DRIFT",
                "test-not-used-by-guard", Instant.now().minusSeconds(5).getEpochSecond(), 6380, 5, "");
        ReflectionTestUtils.setField(runtime, "current", expired);
        runtime.guard();
        assertThat(runtime.snapshot()).containsEntry("status", "BASELINE").containsEntry("recoverySource", "TTL_GUARD");
        assertThat(runtime.snapshot()).containsEntry("redisPort", 6379);
    }

    @Test
    void configurationRejectsUnlistedPortsAndExpiredInjectionRequests() {
        var bad = new DemoConfiguration("", "", "", 0, 22, 5, "BASELINE");
        assertThat(bad.valid()).isFalse();
        assertThatThrownBy(() -> DemoConfiguration.fault(UUID.randomUUID().toString(),
                "NACOS_REDIS_CONFIG_DRIFT", Instant.now().minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
