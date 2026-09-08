package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 核验诊断中明确的观测指标数值与原单位；不推断任意自然语言因果关系。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AgentMetricClaims {
    private static final List<String> FIELDS =
            List.of(
                    "evidence",
                    "summary",
                    "knownFacts",
                    "candidateCauses",
                    "evidenceGaps",
                    "recommendation");
    private static final String UNIT = "%|次/秒|毫秒|秒|[A-Za-z/][A-Za-z0-9/%_-]{0,31}";
    private static final Pattern CLAIM =
            Pattern.compile(
                    "(?<![A-Za-z0-9])(?:metrics\\.)?"
                            + "(?<metric>error[ _-]?rate|错误率|RPS|每秒请求数|请求速率|"
                            + "P95(?:\\s*(?:Ms|延迟|耗时|响应时间|latency))?)"
                            + "(?:\\.value)?[ \\t]*(?:\\((?<labelUnit>"
                            + UNIT
                            + ")\\)|(?<percent>%))?"
                            + "[ \\t]*(?:[=:|]|约为|达到|高达|测得|约|为|是|≈|is|was)?[ \\t]*"
                            + "(?:约|≈)?[ \\t]*"
                            + "(?<prefixUnit>百分之)?"
                            + "(?<value>[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?|NaN|Infinity)"
                            + "(?![0-9.])[ \\t]*(?<unit>(?!(?:and|but)\\b)(?:"
                            + UNIT
                            + "))?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE =
            Pattern.compile("ev-[a-f0-9]{8,24}", Pattern.CASE_INSENSITIVE);
    private static final Pattern THRESHOLD =
            Pattern.compile(
                    "[ \\t]*(?:阈值|门限|目标值|建议设置(?:阈值|门限)?|建议将|threshold|target value)[ \\t:=]*",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENT =
            Pattern.compile("当前|实测|实际|最新|current|measured|actual|latest", Pattern.CASE_INSENSITIVE);

    private AgentMetricClaims() {}

    static Set<String> validate(AgentStore.Run run, JsonNode args, List<JsonNode> references) {
        Set<String> measuredReferences = new HashSet<>();
        for (String field : FIELDS) {
            String text =
                    Normalizer.normalize(args.path(field).asText(), Normalizer.Form.NFKC)
                            .replace("`", "")
                            .replace("**", "");
            var claims = CLAIM.matcher(text);
            while (claims.find()) {
                String clause = prefix(text, claims.start());
                if (THRESHOLD.matcher(clause).matches()) continue;
                String metric = metric(claims.group("metric"));
                List<JsonNode> selected = select(text, claims.start(), metric, references);
                if (selected.isEmpty()) throw invalid(field, metric, "没有对应的已引用观测，不能写成实测数值");
                if (selected.stream().anyMatch(entry -> !entry.path("usable").asBoolean()))
                    throw invalid(field, metric, "引用的采样不可用，不能写成实测数值");
                String valueKey = "metrics." + metric + ".value";
                String unitKey = "metrics." + metric + ".unit";
                Set<String> readings = new HashSet<>();
                for (JsonNode entry : selected)
                    readings.add(
                            entry.path("facts").path(valueKey)
                                    + "|"
                                    + entry.path("units").path(unitKey));
                if (readings.size() != 1)
                    throw invalid(field, metric, "多个采样值不同，请在同一行明确引用对应的 ev- ID");
                if (last(CURRENT, clause) >= 0) {
                    Set<String> latest = new HashSet<>();
                    AgentEvidenceRegistry.currentEntries(run)
                            .forEach(entry -> latest.add(entry.path("id").asText()));
                    if (selected.stream()
                            .anyMatch(entry -> !latest.contains(entry.path("id").asText())))
                        throw invalid(field, metric, "不能把旧采样写成当前实测");
                }
                JsonNode entry = selected.get(0);
                JsonNode observed = entry.path("facts").path(valueKey);
                String expectedUnit = unit(entry.path("units").path(unitKey).asText());
                if (!observed.isNumber() || expectedUnit.isBlank())
                    throw invalid(field, metric, "原始数值或单位缺失，保持未知");
                String givenUnit = claims.group("unit");
                if (givenUnit == null) givenUnit = claims.group("labelUnit");
                if (givenUnit == null
                        && (claims.group("percent") != null || claims.group("prefixUnit") != null))
                    givenUnit = "%";
                if (givenUnit == null && metric.equals("rps")) givenUnit = "requests/s";
                if (givenUnit == null
                        && claims.group("metric").toLowerCase(java.util.Locale.ROOT).endsWith("ms"))
                    givenUnit = "ms";
                if (!expectedUnit.equals(unit(givenUnit)))
                    throw invalid(field, metric, "单位须为原始 " + expectedUnit + "，不得隐式换算或省略");
                if (claims.group("labelUnit") != null
                        && !expectedUnit.equals(unit(claims.group("labelUnit"))))
                    throw invalid(field, metric, "指标标签单位与原始单位不一致");
                BigDecimal reported;
                try {
                    reported = new BigDecimal(claims.group("value"));
                    if (Math.abs((long) reported.scale()) > 100) throw new NumberFormatException();
                } catch (NumberFormatException invalidNumber) {
                    throw invalid(field, metric, "不是可核验的有限数值");
                }
                if (observed.decimalValue()
                                .setScale(reported.scale(), RoundingMode.HALF_UP)
                                .compareTo(reported)
                        != 0)
                    throw invalid(
                            field,
                            metric,
                            "与原始 "
                                    + observed.asText()
                                    + " "
                                    + expectedUnit
                                    + " 不符；只允许按展示小数位四舍五入，不能再次乘100");
                if (reported.signum() == 0
                        && observed.decimalValue().signum() != 0
                        && !text.substring(claims.start(), claims.start("value"))
                                .matches(".*[约≈].*"))
                    throw invalid(field, metric, "非零实测不得直接写成零；请保留有效小数位或明确标注约值");
                if (field.equals("evidence"))
                    selected.forEach(value -> measuredReferences.add(value.path("id").asText()));
            }
        }
        return measuredReferences;
    }

    private static List<JsonNode> select(
            String text, int position, String metric, List<JsonNode> references) {
        int start = text.lastIndexOf('\n', position) + 1;
        int end = text.indexOf('\n', position);
        String line = text.substring(start, end < 0 ? text.length() : end);
        Set<String> explicit = new HashSet<>();
        var ids = REFERENCE.matcher(line);
        while (ids.find()) {
            String shortId = ids.group().toLowerCase(java.util.Locale.ROOT);
            List<JsonNode> matches =
                    references.stream()
                            .filter(entry -> entry.path("id").asText().startsWith(shortId))
                            .toList();
            if (matches.size() != 1)
                throw new AgentEvidenceRegistry.ReferenceError("指标引用 ID 不存在、未引用或缩写不唯一：" + shortId);
            explicit.add(matches.get(0).path("id").asText());
        }
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode entry : references) {
            if (!explicit.isEmpty() && !explicit.contains(entry.path("id").asText())) continue;
            if (entry.path("facts").has("metrics." + metric + ".value")
                    || entry.path("units").has("metrics." + metric + ".unit")) selected.add(entry);
        }
        return selected;
    }

    private static String prefix(String text, int end) {
        int start = end;
        while (start > 0 && ",，;；。\n!?！？".indexOf(text.charAt(start - 1)) < 0) start--;
        return text.substring(start, end);
    }

    private static int last(Pattern pattern, String text) {
        int found = -1;
        var matches = pattern.matcher(text);
        while (matches.find()) found = matches.start();
        return found;
    }

    private static String metric(String label) {
        String normalized = label.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("error") || normalized.equals("错误率")) return "errorRate";
        return normalized.startsWith("p95") ? "p95Ms" : "rps";
    }

    private static String unit(String raw) {
        if (raw == null) return "";
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "%", "percent" -> "%";
            case "requests/s", "/s", "次/秒" -> "requests/s";
            case "ms", "毫秒" -> "ms";
            case "s", "second", "seconds", "秒" -> "s";
            default -> raw;
        };
    }

    private static AgentEvidenceRegistry.ReferenceError invalid(
            String field, String metric, String reason) {
        return new AgentEvidenceRegistry.ReferenceError(
                "诊断数值核验失败（" + field + "/" + metric + "）：" + reason);
    }
}
