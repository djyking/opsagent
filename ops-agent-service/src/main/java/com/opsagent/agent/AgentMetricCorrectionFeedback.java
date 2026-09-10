package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次反馈全部诊断字段的数值/引用错误，提供同运行可核验的原值示例，不改写模型参数。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AgentMetricCorrectionFeedback {
    private static final List<String> FIELDS =
            List.of(
                    "evidence",
                    "summary",
                    "knownFacts",
                    "candidateCauses",
                    "evidenceGaps",
                    "recommendation");
    private static final List<String> METRICS = List.of("errorRate", "rps", "p95Ms");

    private AgentMetricCorrectionFeedback() {}

    static String build(AgentStore.Run run, JsonNode args) {
        AgentEvidenceRegistry.hydrate(run);
        List<JsonNode> references = new ArrayList<>();
        boolean structured =
                AgentTools.frozenParameters(run, "ticket_add_analysis")
                        .path("properties")
                        .has("evidenceIds");
        run.state()
                .path("evidenceRegistry")
                .forEach(
                        entry -> {
                            boolean referenced = false;
                            if (structured) {
                                for (JsonNode id : args.path("evidenceIds"))
                                    referenced |= id.asText().equals(entry.path("id").asText());
                            } else {
                                String evidence = args.path("evidence").asText();
                                referenced = evidence.contains(entry.path("id").asText());
                                String source = entry.path("sourceId").asText();
                                referenced |= !source.isBlank() && evidence.contains(source);
                            }
                            if (referenced && AgentEvidenceRegistry.authentic(run, entry))
                                references.add(entry);
                        });
        StringBuilder text =
                new StringBuilder(
                        "请一次核对并纠正全部诊断字段；以下为校验反馈，不是新的观测或执行结果。\n"
                                + "每项数值独立一行：完整 ev- ID metrics.指标.value=原值原单位。"
                                + "对应 ID 必须加入 evidenceIds，全文不得用 ev-abcd... 等省略形式。"
                                + "不要在数值括号内混写单位与说明；单位说明另起一句。\n");
        for (String field : FIELDS) {
            try {
                AgentMetricClaims.validate(
                        run, AgentJson.object().put(field, args.path(field).asText()), references);
            } catch (AgentEvidenceRegistry.ReferenceError failure) {
                text.append(field).append("：").append(failure.getMessage()).append('\n');
            }
        }
        text.append("本运行最新且原文可核验的指标候选（仅在确实引用且仍通过校验时可写为事实）：\n");
        List<JsonNode> available =
                AgentEvidenceRegistry.currentEntries(run).stream()
                        .filter(entry -> entry.path("usable").asBoolean())
                        .filter(entry -> AgentEvidenceRegistry.authentic(run, entry))
                        .toList();
        for (String metric : METRICS) {
            int count = 0;
            for (JsonNode entry : available) {
                JsonNode value = entry.path("facts").path("metrics." + metric + ".value");
                String unit = entry.path("units").path("metrics." + metric + ".unit").asText();
                if (!value.isNumber() || unit.isBlank()) continue;
                text.append(entry.path("id").asText())
                        .append(" metrics.")
                        .append(metric)
                        .append(".value=")
                        .append(value.asText())
                        .append(unit)
                        .append("；采样时间=")
                        .append(entry.path("observedAt").asText())
                        .append('\n');
                if (++count >= 4) break;
            }
            if (count == 0)
                text.append("metrics.")
                        .append(metric)
                        .append(" 没有可核验的原值及原单位；保持未知并说明需补采观测，禁止猜测或补零。\n");
        }
        return text.toString();
    }
}
