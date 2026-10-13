package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.opsagent.common.core.BusinessException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 验证 RAG 入口由 Sentinel FlowRule 执行限流。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RagRateLimiterTest {
    @AfterEach
    void clearRules() {
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    void shouldRejectRequestAfterSentinelQpsIsExceeded() {
        FlowRule rule = new FlowRule(RagRateLimiter.RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(1.0D);
        FlowRuleManager.loadRules(List.of(rule));
        RagRateLimiter limiter =
                new RagRateLimiter(
                        org.mockito.Mockito.mock(AiBudgetGuard.class),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        limiter.check();

        assertThatThrownBy(limiter::check)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求过于频繁");
    }
}
