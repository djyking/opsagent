package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Narrow rule schema: no arbitrary resources, cluster policy or unknown payload fields.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class TrafficRuleValidator {
    static final Set<String> RESOURCES = Set.of("ops-rag-ask", "ops-rag-request");
    static final Set<String> TYPES = Set.of("FLOW", "DEGRADE", "SYSTEM");
    private final ObjectMapper json;

    TrafficRuleValidator(ObjectMapper json) {
        this.json = json;
    }

    JsonNode validate(String type, JsonNode input) {
        if (!TYPES.contains(type)) fail("该规则类型尚未接入真实参数或来源，不支持发布");
        if (input == null
                || !input.isArray()
                || input.size() > 30
                || input.toString().length() > 32768) fail("规则必须为不超过30条的JSON数组");
        var result = json.createArrayNode();
        for (JsonNode rule : input) {
            if (!rule.isObject()) fail("每条规则必须为对象");
            Set<String> fields =
                    switch (type) {
                        case "FLOW" ->
                                Set.of(
                                        "resource",
                                        "grade",
                                        "count",
                                        "strategy",
                                        "refResource",
                                        "controlBehavior",
                                        "warmUpPeriodSec",
                                        "maxQueueingTimeMs",
                                        "limitApp",
                                        "clusterMode");
                        case "DEGRADE" ->
                                Set.of(
                                        "resource",
                                        "grade",
                                        "count",
                                        "timeWindow",
                                        "minRequestAmount",
                                        "statIntervalMs",
                                        "slowRatioThreshold",
                                        "limitApp");
                        default ->
                                Set.of(
                                        "highestSystemLoad",
                                        "highestCpuUsage",
                                        "avgRt",
                                        "maxThread",
                                        "qps");
                    };
            rule.fieldNames()
                    .forEachRemaining(
                            field -> {
                                if (!fields.contains(field)) fail("规则包含不支持的字段：" + field);
                            });
            ObjectNode normalized = json.createObjectNode();
            if (!type.equals("SYSTEM")) {
                String resource = rule.path("resource").asText();
                if (!RESOURCES.contains(resource)) fail("资源不在纳管范围内");
                if (type.equals("DEGRADE") && !resource.equals("ops-rag-request"))
                    fail("熔断仅适用于覆盖实际处理耗时的 ops-rag-request 资源");
                normalized.put("resource", resource);
                if (rule.has("limitApp") && !rule.path("limitApp").asText().equals("default"))
                    fail("当前尚未接入调用方身份，limitApp只能为default");
                normalized.put("limitApp", "default");
            }
            if (type.equals("FLOW")) {
                int grade = integer(rule, "grade", 1, 0, 1);
                normalized.put("grade", grade);
                normalized.put("count", number(rule, "count", -1, 0, 100000));
                int strategy = integer(rule, "strategy", 0, 0, 2);
                // Current explicit entry has no reliable caller context for chain mode.
                if (strategy == 2) fail("链路模式尚未接入稳定调用上下文，请使用直接或关联模式");
                normalized.put("strategy", strategy);
                String ref = rule.path("refResource").asText("");
                if (strategy == 1
                        && (!RESOURCES.contains(ref) || ref.equals(rule.path("resource").asText())))
                    fail("关联模式必须选择另一个纳管资源");
                normalized.put("refResource", strategy == 1 ? ref : "");
                int behavior = integer(rule, "controlBehavior", 0, 0, 2);
                if (grade == 0 && behavior != 0) fail("线程数模式仅支持快速失败");
                normalized.put("controlBehavior", behavior);
                normalized.put("warmUpPeriodSec", integer(rule, "warmUpPeriodSec", 10, 1, 3600));
                normalized.put(
                        "maxQueueingTimeMs", integer(rule, "maxQueueingTimeMs", 500, 0, 30000));
                if (rule.path("clusterMode").asBoolean(false)) fail("当前未配置集群限流TokenServer");
                normalized.put("clusterMode", false);
            } else if (type.equals("DEGRADE")) {
                int grade = integer(rule, "grade", 0, 0, 2);
                normalized.put("grade", grade);
                normalized.put(
                        "count",
                        number(rule, "count", -1, grade == 0 ? 1 : 0, grade == 1 ? 1 : 600000));
                normalized.put("timeWindow", integer(rule, "timeWindow", 10, 1, 3600));
                normalized.put("minRequestAmount", integer(rule, "minRequestAmount", 5, 1, 10000));
                normalized.put(
                        "statIntervalMs", integer(rule, "statIntervalMs", 1000, 1000, 600000));
                normalized.put("slowRatioThreshold", number(rule, "slowRatioThreshold", 1, 0, 1));
            } else {
                if (input.size() > 1) fail("系统保护仅允许一条明确的聚合规则");
                boolean any = false;
                for (String field :
                        List.of(
                                "highestSystemLoad",
                                "highestCpuUsage",
                                "avgRt",
                                "maxThread",
                                "qps")) {
                    double value =
                            number(
                                    rule,
                                    field,
                                    -1,
                                    -1,
                                    field.equals("highestCpuUsage") ? 1 : 1000000);
                    normalized.put(field, value);
                    any |= value >= 0;
                }
                if (!any) fail("至少设置一项系统保护阈值，或删除规则");
            }
            result.add(normalized);
        }
        return result;
    }

    JsonNode appliedProjection(String type, JsonNode input) {
        if (!input.isArray()) return json.createArrayNode();
        var reduced = json.createArrayNode();
        for (JsonNode value : input) {
            ObjectNode row = json.createObjectNode();
            var fields =
                    switch (type) {
                        case "FLOW" ->
                                List.of(
                                        "resource",
                                        "grade",
                                        "count",
                                        "strategy",
                                        "refResource",
                                        "controlBehavior",
                                        "warmUpPeriodSec",
                                        "maxQueueingTimeMs",
                                        "limitApp",
                                        "clusterMode");
                        case "DEGRADE" ->
                                List.of(
                                        "resource",
                                        "grade",
                                        "count",
                                        "timeWindow",
                                        "minRequestAmount",
                                        "statIntervalMs",
                                        "slowRatioThreshold",
                                        "limitApp");
                        default ->
                                List.of(
                                        "highestSystemLoad",
                                        "highestCpuUsage",
                                        "avgRt",
                                        "maxThread",
                                        "qps");
                    };
            fields.forEach(
                    f -> {
                        if (value.hasNonNull(f)) row.set(f, value.get(f));
                    });
            reduced.add(row);
        }
        return validate(type, reduced);
    }

    boolean equivalent(String type, JsonNode a, JsonNode b) {
        try {
            return sorted(validate(type, a)).equals(sorted(appliedProjection(type, b)));
        } catch (BusinessException ignored) {
            return false;
        }
    }

    private List<String> sorted(JsonNode rules) {
        List<String> result = new ArrayList<>();
        rules.forEach(r -> result.add(r.toString()));
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static int integer(JsonNode node, String name, int fallback, int min, int max) {
        if (node.has(name) && !node.path(name).isIntegralNumber()) fail(name + "必须为整数");
        int value = node.path(name).asInt(fallback);
        if (value < min || value > max) fail(name + "超出允许范围");
        return value;
    }

    private static double number(
            JsonNode node, String name, double fallback, double min, double max) {
        if (node.has(name) && !node.path(name).isNumber()) fail(name + "必须为数字");
        double value = node.path(name).asDouble(fallback);
        if (!Double.isFinite(value) || value < min || value > max) fail(name + "超出允许范围");
        return value;
    }

    private static void fail(String message) {
        throw new BusinessException(ErrorCode.VALIDATION, message);
    }
}
