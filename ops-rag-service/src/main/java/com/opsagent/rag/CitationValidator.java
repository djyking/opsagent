package com.opsagent.rag;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 移除模型生成但检索结果中不存在的片段引用，防止伪造来源被当成可信证据。
 *
 * @author heyu
 * @since 2026/9/1
 */
@Component
public class CitationValidator {
    private static final Pattern CITATION = Pattern.compile("\\[chunk:(\\d+)]");
    private static final Pattern SOURCE = Pattern.compile("\\[S(\\d+)([^\\[\\]\\r\\n]*)]");
    private static final Pattern STANDARD_SOURCE = Pattern.compile("\\[S\\d+]");
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\\r\\n]+[。！？!?；;\\r\\n]*");
    private static final String COVERAGE_MODIFIERS =
            "\\s*(?:(?:直接|明确|充分|完整|具体|详细|系统|相关|完全|全面|实际|有效|足够|清楚|准确|进一步)\\s*){0,6}";
    private static final Pattern SOURCE_SUBJECT =
            Pattern.compile(
                    "^(\\s*)(?:\\[S\\d+]\\s*(?:(?:与|和|及|以及|、|，)\\s*)?)+"
                            + "(?=(?:均|都|仅|只|主要)?(?:针对|涉及|介绍|记载|说明|描述|未|没有|不|无法|不能))");
    private static final Pattern PUBLIC_COVERAGE_GAP =
            Pattern.compile(
                    "(?:未|没有|不)"
                            + COVERAGE_MODIFIERS
                            + "(?:涉及|包含|覆盖|涵盖|提供|提及|给出|适用)"
                            + "|没有(?:关于|针对|相关)"
                            + "|(?:未找到|没有找到|找不到|未检索到|没有检索到)[^。！？!?，,；;\\r\\n]{0,30}"
                            + "(?:相关|关于|针对|依据|证据|资料|材料|信息|内容)"
                            + "|(?:证据|依据|资料|材料|信息)(?:仍然|明显|仍|尚)?不足"
                            + "|(?:缺少|缺乏|没有)(?:足够的?|相关的?|任何)?(?:证据|依据|资料|材料|信息|内容|记录)"
                            + "|(?:无法|不能|不足以)"
                            + COVERAGE_MODIFIERS
                            + "(?:据此|据现有(?:资料|信息|内容))?"
                            + COVERAGE_MODIFIERS
                            + "(?:回答|作答|解答)"
                            + "|(?:与|和)(?:本次|当前|所问|该|此)?(?:问题|提问|需求|主题)(?:不直接相关|无关)"
                            + "|(?:不能|不|不足以)构成(?:本次|当前|该)?(?:问题的?)?(?:依据|证据)");
    private static final Pattern INPUT_CONDITION =
            Pattern.compile(
                    "(?:未|没有)"
                            + COVERAGE_MODIFIERS
                            + "(?:提供|包含)[^。！？!?，,；;\\r\\n]{1,40}"
                            + "(?:时|则)(?=\\s*(?:[，,]\\s*)?(?:(?:系统|服务端?|程序|接口|客户端)\\s*)?"
                            + "(?:会|应|将|导致|返回|认证|拒绝|报错|失效|失败|成功))");

    String validate(String answer, List<RetrievedChunk> chunks) {
        Set<Long> allowed =
                chunks.stream().map(RetrievedChunk::chunkId).collect(Collectors.toSet());
        Matcher matcher = CITATION.matcher(answer);
        StringBuilder safe = new StringBuilder();
        while (matcher.find()) {
            long id = Long.parseLong(matcher.group(1));
            matcher.appendReplacement(safe, allowed.contains(id) ? matcher.group() : "[无效引用已移除]");
        }
        matcher.appendTail(safe);
        return safe.toString();
    }

    Validation validateContext(String answer, List<ContextAssembler.ContextSource> sources) {
        Set<String> allowed =
                sources.stream()
                        .map(ContextAssembler.ContextSource::sourceId)
                        .collect(Collectors.toSet());
        Matcher matcher = SOURCE.matcher(answer);
        StringBuilder safe = new StringBuilder();
        int invalid = 0;
        while (matcher.find()) {
            String sourceId = "S" + matcher.group(1);
            if (!matcher.group(2).isEmpty()) {
                matcher.appendReplacement(safe, "");
                invalid++;
            } else if (allowed.contains(sourceId)) {
                matcher.appendReplacement(safe, matcher.group());
            } else {
                matcher.appendReplacement(safe, "[无效引用已移除]");
                invalid++;
            }
        }
        matcher.appendTail(safe);
        return new Validation(safe.toString(), invalid);
    }

    Validation validateContext(
            String answer,
            List<ContextAssembler.ContextSource> sources,
            boolean publicKnowledgeScope) {
        Validation validation = validateContext(answer, sources);
        if (!publicKnowledgeScope) return validation;
        Matcher sentences = SENTENCE.matcher(validation.answer());
        StringBuilder safe = new StringBuilder();
        int invalid = validation.invalidCount();
        while (sentences.find()) {
            String sentence = sentences.group();
            String withoutCitations = STANDARD_SOURCE.matcher(sentence).replaceAll("");
            if (STANDARD_SOURCE.matcher(sentence).find() && publicCoverageGap(withoutCitations)) {
                invalid += (int) STANDARD_SOURCE.matcher(sentence).results().count();
                sentences.appendReplacement(
                        safe, Matcher.quoteReplacement(removeCoverageCitations(sentence)));
            } else {
                sentences.appendReplacement(safe, Matcher.quoteReplacement(sentence));
            }
        }
        sentences.appendTail(safe);
        return new Validation(safe.toString(), invalid);
    }

    private String removeCoverageCitations(String sentence) {
        Matcher subject = SOURCE_SUBJECT.matcher(sentence);
        if (subject.find()) {
            long sourceCount = STANDARD_SOURCE.matcher(subject.group()).results().count();
            sentence =
                    subject.replaceFirst(
                            Matcher.quoteReplacement(
                                    subject.group(1) + (sourceCount > 1 ? "这些资料" : "该资料")));
        }
        return STANDARD_SOURCE.matcher(sentence).replaceAll("");
    }

    private boolean publicCoverageGap(String sentence) {
        Matcher gaps = PUBLIC_COVERAGE_GAP.matcher(sentence);
        while (gaps.find()) {
            // A documented input condition (e.g. missing username causes authentication failure)
            // is a technical fact, rather than a claim that the retrieved material lacks coverage.
            if (!INPUT_CONDITION
                    .matcher(sentence)
                    .region(gaps.start(), sentence.length())
                    .lookingAt()) return true;
        }
        return false;
    }

    /**
     * 返回移除伪造 Source ID 后的回答和无效引用数量。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Validation(String answer, int invalidCount) {}
}
