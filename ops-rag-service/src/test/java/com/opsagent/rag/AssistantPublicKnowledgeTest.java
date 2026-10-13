package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

/**
 * 检索存在但问题未被覆盖时允许通用补充，限定资料与现场问题仍保留证据边界。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AssistantPublicKnowledgeTest {
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final OfficialDocumentationClient official = mock(OfficialDocumentationClient.class);
    private final RerankService rerank = mock(RerankService.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private RagService rag;

    @BeforeEach
    void setup() {
        var ai = new AiProperties();
        ai.setEnabled(true);
        var settings = new AiProperties.ProviderSettings();
        settings.setApiKey("unit-test-placeholder");
        settings.setModel("test-model");
        ai.setProviders(Map.of("deepseek", settings));
        var properties = new RagProperties();
        when(rerank.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(call -> new RerankService.Outcome(call.getArgument(1), false, null));
        rag =
                new RagService(
                        knowledge,
                        properties,
                        ai,
                        new PromptBuilder(new PromptTemplateLoader(), properties, ai),
                        mock(LlmInvocationService.class),
                        new CitationValidator(),
                        rerank,
                        new ContextAssembler(properties, metrics),
                        metrics,
                        mock(CmdbAnswerService.class),
                        mock(OperationsAnswerService.class));
        ReflectionTestUtils.setField(
                rag, "officialKnowledge", new OfficialKnowledgeFallback(official));
        when(official.search(anyString()))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("status", "UNAVAILABLE"));
    }

    @AfterEach
    void cleanup() {
        metrics.close();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedPublicCandidatesAreRemovedBeforeUsefulUncitedAdvice(boolean visitor) {
        if (visitor) {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    new OpsPrincipal(-7, "visitor", "unit-test", List.of("DEMO")),
                                    null,
                                    List.of()));
        }
        when(knowledge.search(anyString(), anyInt(), isNull())).thenReturn(result(publicCorpus()));
        var plan = rag.prepareStream("Redis连接超时排查有哪些建议？请优先根据知识库用两句话回答，并标出引用。", 5, null);
        assertThat(plan.sources()).isEmpty();
        assertThat(plan.generalFallbackAllowed()).isTrue();
        assertThat(plan.request().systemPrompt())
                .contains("应继续提供有用的通用技术知识", "通用建议", "缺失说明不得标注[S编号]", "通用 AI 回答")
                .contains("‘优先根据知识库’不等于仅限资料", "不能把建议伪装成文档或官方网页记载");
        assertThat(plan.request().userPrompt())
                .contains("两句话")
                .doesNotContain("MySQL", "命中率下降", "Nacos", "异常长事务");
        assertFitsBudget(plan);
        verify(official).search("REDIS_CONNECTION");
        verify(rerank).rerank(anyString(), eq(List.of()), anyInt());
    }

    @Test
    void officialFactsRequireEvidenceWhileGeneralAdviceRemainsAvailable() {
        when(knowledge.search(anyString(), anyInt(), isNull())).thenReturn(result(List.of()));
        var response =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("status", "AVAILABLE")
                        .put("sourceUrl", "https://www.rabbitmq.com/docs/alarms")
                        .put("sourceTitle", "RabbitMQ 资源告警")
                        .put("fetchedAt", "2026-09-08T00:00:00Z");
        response.putArray("citations")
                .addObject()
                .put("title", "RabbitMQ 资源告警")
                .put("excerpt", "RabbitMQ resource alarms block publishing connections.");
        when(official.search("RABBITMQ_ALARMS")).thenReturn(response);
        var plan = rag.prepareStream("RabbitMQ 资源告警如何只读排查？", 5, null);
        assertThat(plan.sources()).hasSize(1);
        assertThat(plan.sources().get(0).sourceType()).isEqualTo("OFFICIAL_WEB");
        assertThat(plan.request().systemPrompt())
                .contains("允许另列明确标注的通用建议", "不附网页引用", "本次[S编号]片段直接支持", "不得笼统归为只读")
                .doesNotContain("片段未覆盖时停止扩展");
        assertThat(plan.request().userPrompt())
                .contains("https://www.rabbitmq.com/docs/alarms", "读取时间");
        assertFitsBudget(plan);
    }

    @ParameterizedTest
    @ValueSource(strings = {"document", "ticket", "internal"})
    void scopedFactsDoNotReceivePublicSupplementation(String scope) {
        var rows = result(List.of(chunk(1, "MySQL 内部连接参数以所选文档记录为准。")));
        when(knowledge.search(anyString(), anyInt(), any())).thenReturn(rows);
        when(knowledge.searchTicket(anyString(), anyInt(), any(), any())).thenReturn(rows);
        String question = scope.equals("internal") ? "本项目的Redis内部地址是什么" : "Redis 连接超时如何排查？";
        var plan =
                rag.prepareStream(
                        question,
                        5,
                        scope.equals("document") ? 77L : null,
                        scope.equals("ticket") ? 88L : null,
                        null);
        assertThat(plan.sources()).hasSize(1);
        assertThat(plan.request().userPrompt()).contains("MySQL 内部连接参数");
        assertThat(plan.generalFallbackAllowed()).isFalse();
        assertThat(plan.request().systemPrompt()).doesNotContain("应继续提供有用的通用技术知识", "检索候选不自动等于答案证据");
        if (!scope.equals("internal"))
            assertThat(plan.request().systemPrompt()).contains("没有证据时说明范围内资料不足");
        verifyNoInteractions(official);
    }

    @Test
    void largerAllowanceKeepsRelevantContextWhileReservingPublicInstructions() {
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenReturn(
                        result(
                                List.of(
                                        chunk(1, "Redis 连接排查：" + "网络可达性。".repeat(80)),
                                        chunk(2, "Redis 连接池：" + "等待队列情况。".repeat(80)),
                                        chunk(3, "Redis 服务端：" + "连接数量检查。".repeat(80)))));
        var plan = rag.prepareStream("Redis连接超时如何排查？", 5, null);
        assertThat(plan.sources()).hasSize(3);
        assertFitsBudget(plan);
    }

    @Test
    void realRedisHitRateQuestionKeepsItsSourceWithinTheVisitorFullRequestBudget() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(-7, "visitor", "unit-test", List.of("DEMO")),
                                null,
                                List.of()));
        String question = "Redis 命中率下降时应该核对哪些项目？请根据知识库用一句话回答，并标出引用。";
        String content = "Redis 命中率下降时，核对 key 过期是否集中、淘汰策略、内存碎片率与回源 QPS，避免缓存雪崩。";
        var row =
                Map.<String, Object>of(
                        "chunkId",
                        1003,
                        "documentId",
                        1002,
                        "documentName",
                        "Redis与Nacos排障手册.md",
                        "content",
                        content,
                        "pageStart",
                        1,
                        "pageEnd",
                        1,
                        "retrievalMode",
                        "BM25");
        var chunks = List.of(RetrievedChunk.from(row));
        var assembled =
                new ContextAssembler(new RagProperties(), metrics).assemble(chunks, chunks, false);
        assertThat(AssistantTokenBudget.bytes(assembled.text())).isEqualTo(207);
        when(knowledge.search(anyString(), anyInt(), isNull())).thenReturn(result(publicCorpus()));

        var plan = rag.prepareStream(question, 5, null);

        assertThat(plan.sources()).hasSize(1);
        assertThat(plan.sources().get(0).documentId()).isEqualTo(1002);
        assertThat(plan.sources().get(0).chunkId()).isEqualTo(1003);
        assertThat(plan.request().userPrompt()).contains(content);
        assertThat(plan.request().userPrompt()).doesNotContain("配置不一致", "MySQL", "异常长事务");
        verify(rerank)
                .rerank(
                        anyString(),
                        argThat(
                                candidates ->
                                        candidates.size() == 1
                                                && candidates.get(0).chunkId() == 1003),
                        anyInt());
        assertThat(plan.request().systemPrompt()).contains("通用建议");
        assertThat(plan.request().priorReservedTokens()).isEqualTo(3780);
        assertFitsBudget(plan);
        verifyNoInteractions(official);
    }

    @Test
    void explicitSourceOnlyInstructionRemainsAnExceptionToGeneralSupplementation() {
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenReturn(result(List.of(chunk(1, "Redis 命中率下降时核对 key 过期。"))));
        var plan = rag.prepareStream("Redis 连接超时如何排查？仅根据给定资料回答，没有提供则说明。", 5, null);
        assertThat(plan.request().systemPrompt()).contains("仅/只依据给定资料回答时，禁止通用补充");
        assertThat(plan.request().userPrompt()).contains("仅根据给定资料回答");
    }

    @Test
    void relevantOfficialConnectionEvidenceIsKeptWhileUnrelatedOfficialChunksAreRemoved() {
        when(knowledge.search(anyString(), anyInt(), isNull())).thenReturn(result(publicCorpus()));
        var response =
                JsonNodeFactory.instance
                        .objectNode()
                        .put("status", "AVAILABLE")
                        .put("sourceUrl", "https://redis.io/docs/latest/develop/reference/clients/")
                        .put("sourceTitle", "Redis client handling")
                        .put("fetchedAt", "2026-09-08T00:00:00Z");
        var citations = response.putArray("citations");
        citations
                .addObject()
                .put("title", "Redis client handling")
                .put("excerpt", "Connections may time out when the configured timeout is reached.");
        citations
                .addObject()
                .put("title", "Redis与Nacos手册")
                .put("excerpt", "Nacos configuration requires a namespace.");
        citations
                .addObject()
                .put("title", "Redis client handling")
                .put("excerpt", "Redis 命中率下降时核对 key 过期与淘汰策略。");
        when(official.search("REDIS_CONNECTION")).thenReturn(response);

        var plan = rag.prepareStream("Redis连接超时如何排查？", 5, null);

        assertThat(plan.sources()).hasSize(1);
        assertThat(plan.sources().get(0).sourceType()).isEqualTo("OFFICIAL_WEB");
        assertThat(plan.sources().get(0).sourceUrl())
                .isEqualTo("https://redis.io/docs/latest/develop/reference/clients/");
        assertThat(plan.request().userPrompt())
                .contains("configured timeout")
                .doesNotContain("MySQL", "Nacos", "命中率下降");
        verify(rerank).rerank(anyString(), argThat(chunks -> chunks.size() == 1), anyInt());
        assertFitsBudget(plan);
    }

    @Test
    void comparingExplicitProductsKeepsSeparatelyRelevantChunks() {
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenReturn(
                        result(
                                List.of(
                                        chunk(1, "Redis 连接超时时核对 socket timeout。"),
                                        chunk(2, "MySQL 连接超时时核对 max_connections。"),
                                        chunk(3, "Nacos 配置不一致时核对 namespace。"))));

        var plan = rag.prepareStream("比较 Redis 与 MySQL 的连接超时排查。", 5, null);

        assertThat(plan.sources()).hasSize(2);
        assertThat(plan.request().userPrompt())
                .contains("socket timeout", "max_connections")
                .doesNotContain("Nacos", "namespace");
        verifyNoInteractions(official);
    }

    private List<Map<String, Object>> publicCorpus() {
        return List.of(
                corpusChunk(
                        1001,
                        1001,
                        0,
                        "MySQL连接池排障手册.md",
                        "数据库连接超时时，先检查 Hikari active、idle、pending 指标，再核对 MySQL max_connections"
                                + " 与慢查询。不要直接无限扩大连接池。"),
                corpusChunk(
                        1002,
                        1001,
                        1,
                        "MySQL连接池排障手册.md",
                        "连接池耗尽的临时措施包括限制入口流量、终止异常长事务和降低慢 SQL 并发；恢复后应补充容量评估。"),
                corpusChunk(
                        1003,
                        1002,
                        0,
                        "Redis与Nacos排障手册.md",
                        "Redis 命中率下降时，核对 key 过期是否集中、淘汰策略、内存碎片率与回源 QPS，避免缓存雪崩。"),
                corpusChunk(
                        1004,
                        1002,
                        1,
                        "Redis与Nacos排障手册.md",
                        "Nacos 配置不一致时，确认 dataId、group、namespace 和客户端长轮询日志，并比较各实例 actuator info。"));
    }

    private Map<String, Object> corpusChunk(
            long id, long doc, int index, String title, String content) {
        return Map.of(
                "chunkId",
                id,
                "documentId",
                doc,
                "chunkIndex",
                index,
                "documentName",
                title,
                "content",
                content,
                "pageStart",
                1,
                "pageEnd",
                1,
                "retrievalMode",
                "BM25");
    }

    private void assertFitsBudget(RagService.StreamPlan plan) {
        assertThat(
                        AssistantTokenBudget.promptUpperBound(plan.request())
                                + plan.request().priorReservedTokens()
                                + 2048)
                .isLessThanOrEqualTo(AssistantTokenBudget.LIMIT);
    }

    private KnowledgeClient.Envelope<List<Map<String, Object>>> result(
            List<Map<String, Object>> chunks) {
        return new KnowledgeClient.Envelope<>(0, "ok", chunks, "test");
    }

    private Map<String, Object> chunk(long id, String content) {
        return Map.of(
                "chunkId",
                id,
                "documentId",
                77L,
                "chunkIndex",
                (int) id,
                "content",
                content,
                "documentName",
                "Redis手册",
                "retrievalMode",
                "BM25");
    }
}
