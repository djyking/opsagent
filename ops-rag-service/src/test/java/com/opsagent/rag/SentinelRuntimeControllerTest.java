package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 验证运行规则取自 Sentinel 内存状态，仅返回问答资源与实际已注册计数。
 *
 * @author heyu
 * @since 2026/9/3
 */
class SentinelRuntimeControllerTest {
    @Test
    void shouldExposeRuntimeRulesWithoutUnrelatedResourcesOrFabricatedCounters() {
        var previous = FlowRuleManager.getRules();
        var metrics = new SimpleMeterRegistry();
        try {
            FlowRule active = new FlowRule(RagRateLimiter.RESOURCE);
            active.setGrade(1);
            active.setCount(7);
            FlowRule privateRule = new FlowRule("private-resource-not-for-public-summary");
            privateRule.setCount(1);
            FlowRuleManager.loadRules(List.of(active, privateRule));
            var controller = new SentinelRuntimeController(metrics);
            var before = controller.state().data();
            assertThat(before.rules()).hasSize(1);
            assertThat(before.rules().get(0).count()).isEqualTo(7);
            assertThat(before.rules().get(0).grade()).isEqualTo("QPS");
            assertThat(before.passedTotal()).isNull();
            assertThat(before.blockedTotal()).isNull();
            metrics.counter("opsagent.rag.sentinel.passed").increment(4);
            metrics.counter("opsagent.rag.sentinel.blocked").increment(2);
            var after = controller.state().data();
            assertThat(after.passedTotal()).isEqualTo(4);
            assertThat(after.blockedTotal()).isEqualTo(2);
            assertThat(after.observedAt()).isNotBlank();
        } finally {
            FlowRuleManager.loadRules(previous);
            metrics.close();
        }
    }
}
