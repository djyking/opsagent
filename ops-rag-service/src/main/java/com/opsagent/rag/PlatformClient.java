package com.opsagent.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 以请求用户身份读取服务目录，仅接收回答需要的白名单字段。
 *
 * @author heyu
 * @since 2026/9/3
 */
@FeignClient(name = "ops-platform-service", url = "${ops.rag.platform-url:}")
interface PlatformClient {
    @GetMapping("/api/platform/cmdb/cis")
    KnowledgeClient.Envelope<List<Ci>> cis();

    @GetMapping("/api/platform/cmdb/relations")
    KnowledgeClient.Envelope<List<Relation>> relations();

    @GetMapping("/api/platform/operations/context")
    KnowledgeClient.Envelope<OperationsContext> operations();

    /**
     * 运维事实的严格白名单投影；未知字段（包括配置正文）丢弃。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OperationsContext(
            String capturedAt,
            String source,
            String status,
            String summary,
            int windowMinutes,
            List<Target> targets,
            List<Metric> metrics,
            List<Risk> risks,
            Nacos nacos,
            Sentinel sentinel) {}

    /**
     * 实际抓取目标健康。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Target(String service, String ciCode, String health, String observedAt) {}

    /**
     * 实测指标与明确区分的趋势估计。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Metric(
            String id,
            String label,
            String job,
            String unit,
            Double currentValue,
            Double forecastValue,
            Double slopePerMinute,
            int sampleCount,
            String status,
            String method,
            String reason,
            String observedAt) {}

    /**
     * 基于指标的风险提示，不是已发生事故。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Risk(
            String key,
            String severity,
            String title,
            String detail,
            String evidence,
            String recommendation) {}

    /**
     * 注册中心计数，不接收 dataId、配置或连接地址。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Nacos(
            String status,
            String message,
            Integer serviceCount,
            Integer healthyInstanceCount,
            Integer configurationCount) {}

    /**
     * Sentinel 运行指标白名单。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Sentinel(
            String status,
            String message,
            String ruleSource,
            List<SentinelRule> rules,
            Double passedTotal,
            Double blockedTotal,
            String metricsObservedAt) {}

    /**
     * 不接收任意资源参数或扩展配置。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SentinelRule(String resource, String grade, double count, String controlBehavior) {}

    /**
     * 服务目录安全投影，不接收 endpoint、凭据或自由描述。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ci(
            String ciCode,
            String ciName,
            String ciType,
            String environment,
            String status,
            String updateTime) {}

    /**
     * 已登记的有向关系。
     *
     * @author heyu
     * @since 2026/9/3
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Relation(
            String sourceCiCode, String targetCiCode, String relationType, String createTime) {}
}
