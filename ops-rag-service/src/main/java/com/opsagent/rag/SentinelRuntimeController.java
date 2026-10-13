package com.opsagent.rag;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.opsagent.common.core.ApiResponse;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 只读投影实际生效的问答限流规则及本进程计数，不暴露任意配置内容。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
public class SentinelRuntimeController {
    private final MeterRegistry metrics;

    SentinelRuntimeController(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/api/rag/runtime/sentinel")
    ApiResponse<RuntimeState> state() {
        List<Rule> rules =
                FlowRuleManager.getRules().stream()
                        .filter(rule -> RagRateLimiter.RESOURCE.equals(rule.getResource()))
                        .map(
                                rule ->
                                        new Rule(
                                                rule.getResource(),
                                                rule.getGrade() == 1 ? "QPS" : "THREAD",
                                                rule.getCount(),
                                                switch (rule.getControlBehavior()) {
                                                    case 1 -> "WARM_UP";
                                                    case 2 -> "RATE_LIMITER";
                                                    case 3 -> "WARM_UP_RATE_LIMITER";
                                                    default -> "DEFAULT";
                                                }))
                        .toList();
        return ApiResponse.success(
                new RuntimeState(
                        "AVAILABLE",
                        "Sentinel runtime FlowRuleManager",
                        rules,
                        count("opsagent.rag.sentinel.passed"),
                        count("opsagent.rag.sentinel.blocked"),
                        Instant.now().toString()));
    }

    private Double count(String name) {
        var counter = metrics.find(name).counter();
        return counter == null ? null : counter.count();
    }

    /**
     * 单条实际运行规则。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Rule(String resource, String grade, double count, String controlBehavior) {}

    /**
     * 计数仅表示本进程启动以来的 Sentinel 通过/拦截，不含独立预算拦截。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record RuntimeState(
            String status,
            String ruleSource,
            List<Rule> rules,
            Double passedTotal,
            Double blockedTotal,
            String observedAt) {}
}
