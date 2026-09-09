package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

/**
 * 验证模型伪造的 Citation 不会作为可信来源返回。
 *
 * @author heyu
 * @since 2026/9/2
 */
class CitationValidatorTest {
    @Test
    void shouldRemoveUnknownCitationAndKeepRetrievedCitation() {
        RetrievedChunk chunk =
                new RetrievedChunk(10L, 1L, 0, "content", "doc.md", null, 1, "", 1.0D);

        String answer =
                new CitationValidator().validate("真实 [chunk:10]，伪造 [chunk:999]。", List.of(chunk));

        assertThat(answer).contains("[chunk:10]").doesNotContain("[chunk:999]");
    }

    @Test
    void shouldRejectUnknownContextSourceId() {
        RetrievedChunk chunk =
                new RetrievedChunk(10L, 1L, 0, "content", "doc.md", null, 1, "", 1.0D);
        ContextAssembler.ContextSource source =
                new ContextAssembler.ContextSource("S1", chunk, false, null);

        CitationValidator.Validation result =
                new CitationValidator().validateContext("真实 [S1]，伪造 [S9]。", List.of(source));

        assertThat(result.answer()).contains("[S1]").doesNotContain("[S9]");
        assertThat(result.invalidCount()).isEqualTo(1);
    }

    @Test
    void shouldDropMalformedSourceMarkersWithoutChangingOrdinaryBracketedText() {
        RetrievedChunk chunk =
                new RetrievedChunk(10L, 1L, 0, "content", "doc.md", null, 1, "", 1.0D);
        ContextAssembler.ContextSource source =
                new ContextAssembler.ContextSource("S1", chunk, false, null);

        CitationValidator.Validation result =
                new CitationValidator()
                        .validateContext(
                                "事实[S1]；未知[S9]；缺失[S1不适用]；伪造[S9不存在]；注释[S1 not applicable]；"
                                        + "[普通说明][INFO][SLA]。",
                                List.of(source));

        assertThat(result.answer()).isEqualTo("事实[S1]；未知[无效引用已移除]；缺失；伪造；注释；[普通说明][INFO][SLA]。");
        assertThat(result.invalidCount()).isEqualTo(4);
    }

    @Test
    void shouldNeverPromoteMalformedOrUnknownMarkersToRetrievedEvidence() {
        CitationValidator.Validation result =
                new CitationValidator()
                        .validateContext(
                                "建议[S1]、缺失[S1不适用]、未知[S9999999999999999999999999]、普通[建议]。",
                                List.of());

        assertThat(result.answer()).isEqualTo("建议[无效引用已移除]、缺失、未知[无效引用已移除]、普通[建议]。");
        assertThat(result.invalidCount()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "知识库未包含Redis排查信息",
                "资料中未覆盖Redis排查方法",
                "文档未提供Redis参数",
                "片段均未提及Redis",
                "上下文不涉及Redis",
                "资料不适用",
                "知识库没有相关依据"
            })
    void shouldRemovePublicCoverageGapCitationsAtSentenceScope(String gap) {
        var chunk = new RetrievedChunk(10L, 1L, 0, "content", "doc.md", null, 1, "", 1.0D);
        var sources =
                List.of(
                        new ContextAssembler.ContextSource("S1", chunk, false, null),
                        new ContextAssembler.ContextSource("S2", chunk, false, null));
        String answer = "正常支持句[S1]。" + gap + "[S2]。通用建议：检查网络。";
        var validation = new CitationValidator().validateContext(answer, sources, true);
        assertThat(validation.answer()).isEqualTo("正常支持句[S1]。" + gap + "。通用建议：检查网络。");
        assertThat(validation.invalidCount()).isEqualTo(1);
        assertThat(new CitationValidator().validateContext(answer, sources, false).answer())
                .isEqualTo(answer);
    }

    @Test
    void shouldKeepSupportedNegativeFactsAndReuseOfTheSameSourceElsewhere() {
        var chunk = new RetrievedChunk(10L, 1L, 0, "content", "doc.md", null, 1, "", 1.0D);
        var sources = List.of(new ContextAssembler.ContextSource("S1", chunk, false, null));
        String answer = "文档说明：未提供用户名时认证失败[S1]。资料未覆盖其他方法[S1]。";
        var validation = new CitationValidator().validateContext(answer, sources, true);
        assertThat(validation.answer()).isEqualTo("文档说明：未提供用户名时认证失败[S1]。资料未覆盖其他方法。");
        assertThat(validation.invalidCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[S2]针对Redis缓存命中率下降，未涉及连接超时。",
                "[S2]只介绍缓存命中率，没有涉及连接超时。",
                "现有内容没有提供Redis连接超时的信息[S2]。",
                "现有内容未覆盖Redis连接超时[S2]。",
                "[S2]不适用。",
                "这些片段主要讲MySQL连接池，无法据此回答Redis连接超时[S2]。",
                "现有内容没有关于Redis连接超时的排查材料[S2]。",
                "找不到相关排查资料[S2]。",
                "没有找到与该问题相关的依据[S2]。",
                "未检索到关于该问题的内容[S2]。",
                "当前材料未涵盖连接超时[S2]。",
                "尚未给出连接超时的操作步骤[S2]。",
                "资料不足，无法直接回答[S2]。",
                "目前证据不足[S2]。",
                "与当前问题无关[S2]。",
                "这不能构成当前问题的依据[S2]。"
            })
    void shouldRemoveCoverageCitationsWithoutRequiringANounSubject(String gap) {
        var chunk = new RetrievedChunk(10L, 1L, 0, "content", "doc.md", null, 1, "", 1.0D);
        var sources =
                List.of(
                        new ContextAssembler.ContextSource("S1", chunk, false, null),
                        new ContextAssembler.ContextSource("S2", chunk, false, null));
        String original = "本产品不支持该功能[S1]。" + gap + "通用建议：检查网络。";
        var result = new CitationValidator().validateContext(original, sources, true);
        assertThat(result.answer())
                .isEqualTo(original.replace("[S2]", gap.startsWith("[S2]") ? "该资料" : ""));
        assertThat(result.invalidCount()).isEqualTo(1);
        assertThat(new CitationValidator().validateContext(original, sources, false).answer())
                .isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "该产品不支持此功能[S1]。",
                "该产品的协议没有启用压缩[S1]。",
                "网络错误与磁盘故障无关[S1]。",
                "请求未提供用户名时，服务会拒绝认证[S1]。",
                "文档明确写明Redis不支持此功能[S1]。",
                "请求未明确提供用户名时，服务会拒绝认证[S1]。"
            })
    void shouldPreserveOrdinarySupportedNegativeTechnicalFacts(String answer) {
        var chunk = new RetrievedChunk(10L, 1L, 0, "content", "doc.md", null, 1, "", 1.0D);
        var sources = List.of(new ContextAssembler.ContextSource("S1", chunk, false, null));
        var result = new CitationValidator().validateContext(answer, sources, true);
        assertThat(result.answer()).isEqualTo(answer);
        assertThat(result.invalidCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "未直接覆盖", "未 直接 覆盖", "没有明确提供", "未充分涵盖", "没有完整覆盖",
                "未具体给出", "没有详细提及", "未系统覆盖", "没有相关提及", "未明确充分覆盖"
            })
    void shouldRecognizeNaturallyModifiedCoveragePredicates(String predicate) {
        var chunk = new RetrievedChunk(10L, 1L, 0, "content", "doc.md", null, 1, "", 1.0D);
        var sources = List.of(new ContextAssembler.ContextSource("S1", chunk, false, null));
        String original = "现有内容" + predicate + "连接超时的排查步骤[S1]。";
        var result = new CitationValidator().validateContext(original, sources, true);
        assertThat(result.answer()).isEqualTo(original.replace("[S1]", ""));
        assertThat(result.invalidCount()).isEqualTo(1);
        assertThat(new CitationValidator().validateContext(original, sources, false).answer())
                .isEqualTo(original);
    }

    @Test
    void shouldPreserveTheSubjectWhenDroppingAnInapplicableSourceList() {
        var chunk = new RetrievedChunk(10L, 1L, 0, "content", "doc.md", null, 1, "", 1.0D);
        var sources =
                List.of(
                        new ContextAssembler.ContextSource("S1", chunk, false, null),
                        new ContextAssembler.ContextSource("S2", chunk, false, null));
        var result =
                new CitationValidator()
                        .validateContext(
                                "[S1] 与 [S2] 均针对MySQL，不适用于Redis。[S1]针对缓存，未直接涉及连接超时。",
                                sources,
                                true);
        assertThat(result.answer()).isEqualTo("这些资料均针对MySQL，不适用于Redis。该资料针对缓存，未直接涉及连接超时。");
        assertThat(result.invalidCount()).isEqualTo(3);
    }
}
