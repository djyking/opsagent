package com.opsagent.rag;

import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.IntervalProperty;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Actual Sentinel rolling-window metrics and dynamically loaded rules.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class SentinelTrafficRuntimeController {
    private final ObjectMapper json;

    @Value("${spring.cloud.sentinel.enabled:false}")
    private boolean enabled;

    SentinelTrafficRuntimeController(ObjectMapper json) {
        this.json = json;
    }

    @GetMapping("/api/rag/runtime/traffic")
    ApiResponse<RuntimeState> state() {
        SecurityUsers.current();
        var resources =
                List.of(
                        resource(RagRateLimiter.RESOURCE, "问答入口校验", false),
                        resource(RagRateLimiter.REQUEST_RESOURCE, "问答请求（含流式生命周期）", true));
        JsonNode flow =
                json.valueToTree(
                        FlowRuleManager.getRules().stream()
                                .filter(r -> managed(r.getResource()))
                                .toList());
        JsonNode degrade =
                json.valueToTree(
                        DegradeRuleManager.getRules().stream()
                                .filter(r -> managed(r.getResource()))
                                .toList());
        JsonNode system = json.valueToTree(SystemRuleManager.getRules());
        return ApiResponse.success(
                new RuntimeState(
                        enabled ? "AVAILABLE" : "DISABLED",
                        "ops-rag-service",
                        resources,
                        Map.of("FLOW", flow, "DEGRADE", degrade, "SYSTEM", system),
                        Instant.now()));
    }

    private Resource resource(String name, String label, boolean duration) {
        ClusterNode node = enabled ? ClusterBuilderSlot.getClusterNode(name) : null;
        return new Resource(
                name,
                label,
                node == null ? "NO_SAMPLES" : "AVAILABLE",
                node == null ? null : node.passQps(),
                node == null ? null : node.blockQps(),
                node == null || !duration || node.successQps() <= 0 ? null : node.avgRt(),
                node == null ? null : node.curThreadNum(),
                "Sentinel "
                        + IntervalProperty.INTERVAL
                        + " ms滑动窗口；"
                        + (duration ? "完整请求生命周期；流式响应直到完成/失败/断开" : "仅入口校验；不代表模型响应耗时"));
    }

    private boolean managed(String name) {
        return RagRateLimiter.RESOURCE.equals(name) || RagRateLimiter.REQUEST_RESOURCE.equals(name);
    }

    record Resource(
            String resource,
            String label,
            String status,
            Double passQps,
            Double blockQps,
            Double avgRt,
            Integer activeThreads,
            String measurement) {}

    record RuntimeState(
            String status,
            String serviceId,
            List<Resource> resources,
            Map<String, JsonNode> rules,
            Instant observedAt) {}
}
