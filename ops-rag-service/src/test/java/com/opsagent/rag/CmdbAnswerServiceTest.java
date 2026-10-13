package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

/**
 * 验证真实目录回答、依赖方向、范围优先级与来源白名单。
 *
 * @author heyu
 * @since 2026/9/3
 */
class CmdbAnswerServiceTest {
    private final PlatformClient platform = mock(PlatformClient.class);
    private final CmdbAnswerService cmdb = new CmdbAnswerService(platform);
    private final List<PlatformClient.Ci> cis =
            List.of(
                    new PlatformClient.Ci(
                            "ops-ticket-service",
                            "工单服务",
                            "SERVICE",
                            "local",
                            "ACTIVE",
                            "2026-09-03T11:00:00"),
                    new PlatformClient.Ci(
                            "ops-rag-service",
                            "RAG 服务",
                            "SERVICE",
                            "local",
                            "ACTIVE",
                            "2026-09-03T12:00:00"),
                    new PlatformClient.Ci(
                            "redis", "Redis", "CACHE", "local", "ACTIVE", "2026-09-03T11:00:00"));

    @ParameterizedTest
    @ValueSource(strings = {"目前有哪些服务？列一个清单给我呢", "列出系统服务清单", "服务目录里有什么", "当前有多少服务"})
    void shouldReadCurrentCatalogWithoutCallingKnowledgeOrModel(String question) {
        when(platform.cis()).thenReturn(envelope(cis));
        KnowledgeClient knowledge = mock(KnowledgeClient.class);
        when(knowledge.ticketDocuments(2053L)).thenReturn(envelope(List.of()));
        LlmInvocationService llm = mock(LlmInvocationService.class);
        var rag = rag(knowledge, llm);
        RagService.Answer answer = rag.ask(question, 1, null, 2053L);
        assertThat(answer.answer()).contains("2 项服务", "工单服务", "ops-rag-service", "不代表当前健康");
        assertThat(answer.answer()).doesNotContain("| Redis |");
        assertThat(answer.provider()).isEqualTo("cmdb");
        assertThat(answer.metadata().retrievalMode()).isEqualTo("CMDB");
        assertThat(answer.outputTokens()).isZero();
        RagService.Source source = answer.references().get(0);
        assertThat(source.sourceType()).isEqualTo("CMDB");
        assertThat(source.sourceUrl()).isEqualTo("/itsm/cmdb");
        assertThat(source.sourceUpdatedAt()).isEqualTo("2026-09-03T12:00:00");
        assertThat(Instant.parse(source.sourceRetrievedAt())).isBeforeOrEqualTo(Instant.now());
        assertThat(source.chunkId()).isZero();
        assertThat(source.documentId()).isZero();
        verifyNoInteractions(llm);
        verify(knowledge).ticketDocuments(2053L);
        verify(knowledge, never()).search(any(), anyInt(), any());
        verify(platform, never()).relations();
    }

    @Test
    void diagnosisAndAppendedPageEvidenceMustNotBecomeACatalogRequest() {
        String context = "\n\n当前页面上下文：ops-rag-service。请针对当前对象进行只读分析。"
                + "\n服务目录、依赖关系和健康证据：UNKNOWN";
        String diagnosis = "请分析当前服务的异常现象、证据和可能原因，并说明还需要核对哪些信息。";
        assertThat(cmdb.supports(diagnosis, null)).isFalse();
        assertThat(cmdb.supports(diagnosis + context, null)).isFalse();
        assertThat(cmdb.supports("哪些服务有异常？", null)).isFalse();
        assertThat(cmdb.supports("你好" + context, null)).isFalse();
        assertThat(cmdb.supports("列出服务清单" + context, null)).isTrue();
        assertThat(cmdb.supports("这个服务依赖哪些组件？" + context, null)).isTrue();
        verifyNoInteractions(platform);
    }

    @Test
    void shouldRespectDependencyDirectionAndNotInventUnknownServiceRelations() {
        when(platform.cis()).thenReturn(envelope(cis));
        when(platform.relations())
                .thenReturn(
                        envelope(
                                List.of(
                                        new PlatformClient.Relation(
                                                "ops-ticket-service", "redis", "DEPENDS_ON", null),
                                        new PlatformClient.Relation(
                                                "ops-rag-service",
                                                "ops-ticket-service",
                                                "CALLS",
                                                null))));
        String outgoing = cmdb.answerIfApplicable("工单服务依赖哪些服务？", null).answer();
        assertThat(outgoing).contains("1 条", "Redis（redis）").doesNotContain("RAG 服务（");
        String incoming = cmdb.answerIfApplicable("哪些服务依赖 Redis？", null).answer();
        assertThat(incoming).contains("上游", "1 条", "工单服务（ops-ticket-service）");
        String unknown = cmdb.answerIfApplicable("支付服务依赖哪些服务？", null).answer();
        assertThat(unknown).contains("没有识别到").doesNotContain("工单服务（");
        assertThat(cmdb.answerIfApplicable("支付服务的依赖关系是什么？", null).answer())
                .contains("没有识别到")
                .doesNotContain("工单服务（");
        assertThat(cmdb.answerIfApplicable("查看服务依赖关系", null).answer()).contains("2 条");
    }

    @Test
    void shouldLeaveSelectedDocumentAndNonCatalogQuestionsOnKnowledgePath() {
        assertThat(cmdb.answerIfApplicable("目前有哪些服务？", 1035L)).isNull();
        assertThat(cmdb.answerIfApplicable("Redis 连接超时怎么排查", null)).isNull();
        assertThat(cmdb.answerIfApplicable("服务有哪些常见故障排查方法？", null)).isNull();
        assertThat(cmdb.answerIfApplicable("如何设计服务之间的依赖关系？", null)).isNull();
        verifyNoInteractions(platform);
    }

    @Test
    void shouldReportUnavailableWithoutSubstitutingStaticKnowledgeOrLeakingUpstreamError() {
        when(platform.cis())
                .thenThrow(new IllegalStateException("http://internal:8105/?token=secret"));
        var answer = cmdb.answerIfApplicable("服务清单", null);
        assertThat(answer.answer()).contains("无法读取").doesNotContain("internal", "secret");
        assertThat(answer.references()).isEmpty();
        assertThat(answer.metadata().degradedReason()).isEqualTo("CMDB_UNAVAILABLE");
    }

    @Test
    void shouldDiscardEndpointsAndFreeDescriptionsBeforeFormatting() throws Exception {
        String json =
                "{\"ciCode\":\"test\",\"ciName\":\"测试服务\",\"ciType\":\"SERVICE\","
                    + "\"endpoint\":\"http://user:secret@internal\",\"description\":\"api-key=secret\"}";
        PlatformClient.Ci projected = new ObjectMapper().readValue(json, PlatformClient.Ci.class);
        when(platform.cis()).thenReturn(envelope(List.of(projected)));
        assertThat(cmdb.answerIfApplicable("列出服务清单", null).answer())
                .contains("测试服务")
                .doesNotContain("secret", "internal", "api-key");
    }

    private RagService rag(KnowledgeClient knowledge, LlmInvocationService llm) {
        RagProperties properties = new RagProperties();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        return new RagService(
                knowledge,
                properties,
                new AiProperties(),
                mock(PromptBuilder.class),
                llm,
                new CitationValidator(),
                mock(RerankService.class),
                new ContextAssembler(properties, metrics),
                metrics,
                cmdb,
                mock(OperationsAnswerService.class));
    }

    private <T> KnowledgeClient.Envelope<List<T>> envelope(List<T> data) {
        return new KnowledgeClient.Envelope<>(0, "ok", data, "test");
    }
}
