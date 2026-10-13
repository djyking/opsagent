package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.core.QueryEmbeddingBudget;
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
 * 覆盖预设实时问题、知识优先和缺失后的官方来源回退，不扩大文档与权限范围。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AssistantRoutingTest {
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final OfficialDocumentationClient official = mock(OfficialDocumentationClient.class);
    private final PlatformClient platform = mock(PlatformClient.class);
    private final AiProperties ai = new AiProperties();
    private final PromptBuilder prompts = mock(PromptBuilder.class);
    private final LlmInvocationService invocation = mock(LlmInvocationService.class);
    private final CmdbAnswerService cmdb = mock(CmdbAnswerService.class);
    private final ObservabilityEvidenceClient evidence = mock(ObservabilityEvidenceClient.class);
    private RagService rag;

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setup() {
        ai.setEnabled(false);
        var properties = new RagProperties();
        var metrics = new SimpleMeterRegistry();
        var rerank = mock(RerankService.class);
        when(rerank.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(call -> new RerankService.Outcome(call.getArgument(1), false, null));
        rag =
                new RagService(
                        knowledge,
                        properties,
                        ai,
                        prompts,
                        invocation,
                        new CitationValidator(),
                        rerank,
                        new ContextAssembler(properties, metrics),
                        metrics,
                        cmdb,
                        new OperationsAnswerService(platform, ai));
        ReflectionTestUtils.setField(
                rag, "officialKnowledge", new OfficialKnowledgeFallback(official));
        ReflectionTestUtils.setField(rag, "observationEvidence", evidence);
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "trace"));
        when(official.search(anyString()))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("status", "UNAVAILABLE"));
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
                .put(
                        "excerpt",
                        "RabbitMQ resource alarms block publishing connections when memory or disk"
                                + " limits are exceeded.");
        when(official.search("RABBITMQ_ALARMS")).thenReturn(response);
    }

    @Test
    void runbookRecommendationUsesRealDirectoryAndRetainsServiceBeforeGenericRouting() {
        var catalog = mock(AutomationCatalogClient.class);
        when(catalog.definitions())
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        new AutomationCatalogClient.Definition(
                                                "isolated-recovery", "隔离业务故障诊断与恢复", 4)),
                                "test"));
        ReflectionTestUtils.setField(rag, "runbooks", new RunbookCatalogAnswerService(catalog));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(-7, "visitor", "test", List.of("DEMO")),
                                null,
                                List.of()));
        var plan =
                rag.prepareStream(
                        "推荐相关 Runbook",
                        5,
                        null,
                        null,
                        null,
                        "deepseek",
                        new ObservabilityContext("ops-gateway", "PROD", "15m", null));
        assertThat(plan.immediate().answer()).contains("ops-gateway", "未配置可直接执行", "v4");
        assertThat(plan.immediate().metadata().retrievalMode()).isEqualTo("RUNBOOK_CATALOG");
        verifyNoInteractions(knowledge, official, evidence, invocation);
    }

    @Test
    void defaultCurrentAnomalyQuestionNeverFallsThroughToKnowledgeOrPublicWeb() {
        when(platform.operations()).thenThrow(new IllegalStateException("unavailable"));
        var plan = rag.prepareStream("当前有哪些异常需要关注？", 5, null);
        assertThat(plan.immediate().provider()).isEqualTo("operations");
        assertThat(plan.immediate().answer()).contains("运行数据暂时无法读取").doesNotContain("知识库内容不足");
        verifyNoInteractions(knowledge, official);
    }

    @Test
    void matchedKnowledgeTakesPriorityWithoutPublicRequest() {
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                8,
                                                "documentId",
                                                3,
                                                "content",
                                                "RabbitMQ 资源告警时先检查磁盘与内存",
                                                "documentName",
                                                "本地手册")),
                                "trace"));
        var plan = rag.prepareStream("RabbitMQ 资源告警的常见原因？", 5, null);
        assertThat(plan.sources()).hasSize(1);
        assertThat(plan.sources().get(0).sourceType()).isEqualTo("KNOWLEDGE_DOCUMENT");
        verifyNoInteractions(official);
    }

    @Test
    void emptyOrUnrelatedKnowledgeUsesRealOfficialEvidenceAfterRetrieval() {
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                8,
                                                "documentId",
                                                3,
                                                "content",
                                                "MySQL 连接池排障",
                                                "documentName",
                                                "旧手册")),
                                "trace"));
        var plan = rag.prepareStream("RabbitMQ 资源告警的常见原因？", 5, null);
        var order = inOrder(knowledge, official);
        order.verify(knowledge).search(anyString(), anyInt(), isNull());
        order.verify(official).search("RABBITMQ_ALARMS");
        assertThat(plan.sources()).hasSize(1);
        assertThat(plan.sources().get(0).sourceType()).isEqualTo("OFFICIAL_WEB");
        assertThat(plan.sources().get(0).sourceUrl())
                .isEqualTo("https://www.rabbitmq.com/docs/alarms");
        assertThat(plan.immediate().answer())
                .contains("RabbitMQ resource alarms")
                .doesNotContain("MySQL");
    }

    @Test
    void retrievalOutageCanUsePublicKnowledgeButPermissionDenialCannot() {
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenThrow(new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "offline"));
        assertThat(rag.prepareStream("RabbitMQ 资源告警的常见原因？", 5, null).sources().get(0).sourceType())
                .isEqualTo("OFFICIAL_WEB");
        clearInvocations(official);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "denied"))
                .when(knowledge)
                .search(anyString(), anyInt(), isNull());
        assertThatThrownBy(() -> rag.prepareStream("RabbitMQ 资源告警的常见原因？", 5, null))
                .hasMessage("denied");
        verifyNoInteractions(official);
    }

    @Test
    void selectedDocumentAndInternalFactsNeverWidenToPublicWeb() {
        when(knowledge.search(anyString(), anyInt(), eq(77L)))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "trace"));
        rag.prepareStream("RabbitMQ 资源告警的常见原因？", 5, 77L);
        rag.prepareStream("本项目的RabbitMQ内部地址是什么", 5, null);
        verifyNoInteractions(official);
    }

    @Test
    void unavailableOfficialSourceDoesNotClaimSearchSuccessOrInventReferences() {
        enableModel();
        when(official.search(anyString()))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("status", "UNAVAILABLE"));
        var plan = rag.prepareStream("RabbitMQ 资源告警的常见原因？", 5, null);
        assertThat(plan.sources()).isEmpty();
        assertThat(plan.immediate()).isNull();
        assertThat(plan.metadata().retrievalMode()).isEqualTo("GENERAL_AI");
        assertThat(plan.request().systemPrompt()).contains("不得生成[S编号]", "通用 AI 回答");
    }

    @Test
    void greetingOnServicePageUsesActualSelectedModelWithoutAnyRetrieval() {
        enableModel();
        var plan =
                rag.prepareStream(
                        "你好！\n\n当前页面上下文：ops-rag-service",
                        5,
                        null,
                        null,
                        "用户：早上好\n助手：你好",
                        "deepseek",
                        new ObservabilityContext("ops-rag-service", "PROD", "15m", null));
        assertThat(plan.immediate()).isNull();
        assertThat(plan.question()).isEqualTo("你好！");
        assertThat(plan.request().priorReservedTokens()).isZero();
        assertThat(plan.request().userPrompt()).contains("早上好").doesNotContain("ops-rag-service");
        assertThat(plan.metadata().retrievalMode()).isEqualTo("GENERAL_AI");
        when(invocation.stream(eq("deepseek"), eq("你好！"), eq(plan.request()), any(), isNull()))
                .thenReturn(
                        new LlmInvocationService.Invocation(
                                new LlmResult("你好！", "deepseek", "test-deepseek", 12, 4), 10));
        var answer = rag.stream(plan, delta -> {}, null);
        assertThat(answer.answer()).isEqualTo("你好！");
        assertThat(answer.provider()).isEqualTo("deepseek");
        assertThat(answer.inputTokens()).isEqualTo(12);
        verifyNoInteractions(knowledge, official, platform, cmdb, evidence);
    }

    @Test
    void generalTeachingIgnoresImplicitServiceContextAndRemovesFabricatedCitation() {
        enableModel();
        var plan =
                rag.prepareStream(
                        "什么是布隆过滤器？\n\n当前页面上下文：redis",
                        5,
                        null,
                        null,
                        null,
                        null,
                        new ObservabilityContext("redis", "PROD", "15m", null));
        when(invocation.stream(eq(plan.question()), eq(plan.request()), any(), isNull()))
                .thenReturn(
                        new LlmInvocationService.Invocation(
                                new LlmResult(
                                        "它是一种概率数据结构 [S1]", "deepseek", "test-deepseek", 20, 10),
                                8));
        var answer = rag.stream(plan, delta -> {}, null);
        assertThat(answer.metadata().retrievalMode()).isEqualTo("GENERAL_AI");
        assertThat(answer.references()).isEmpty();
        assertThat(answer.answer()).doesNotContain("[S1]");
        verify(knowledge).search(eq("布隆过滤器"), anyInt(), isNull());
        verifyNoInteractions(official, evidence, platform);
    }

    @Test
    void genericAnswerDuringKnowledgeOutageDisclosesMissingLookup() {
        enableModel();
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenThrow(new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "offline"));
        var plan = rag.prepareStream("什么是布隆过滤器", 5, null);
        assertThat(plan.immediate()).isNull();
        assertThat(plan.metadata().degradedReason()).isEqualTo("KNOWLEDGE_UNAVAILABLE");
        assertThat(plan.request().systemPrompt()).contains("知识服务暂时不可用", "并非成功检索");
    }

    @Test
    void selectedDocumentAndTicketRemainStrictEvenForGenericTeaching() {
        enableModel();
        when(knowledge.search(anyString(), anyInt(), eq(77L)))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "trace"));
        when(knowledge.searchTicket(anyString(), anyInt(), isNull(), eq(88L)))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "trace"));
        assertThat(rag.prepareStream("什么是布隆过滤器", 5, 77L).immediate().metadata().finishReason())
                .isEqualTo("no_evidence");
        assertThat(
                        rag.prepareStream("什么是布隆过滤器", 5, null, 88L, null)
                                .immediate()
                                .metadata()
                                .finishReason())
                .isEqualTo("no_evidence");
        verifyNoInteractions(official);
        verify(invocation, never()).invoke(anyString(), any(LlmRequest.class));
    }

    @Test
    void providerTimeoutKeepsActualReasonAndMarksAnswerIncomplete() {
        enableModel();
        when(invocation.invoke(eq("你好"), any(LlmRequest.class)))
                .thenThrow(
                        new AiProviderException(
                                "deepseek",
                                0,
                                "读取模型响应超时",
                                null,
                                AiProviderException.FailureKind.TIMEOUT));
        var answer = rag.ask("你好", 5);
        assertThat(answer.answer()).contains("读取模型响应超时").doesNotContain("知识库内容不足");
        assertThat(answer.metadata().generationComplete()).isFalse();
        assertThat(answer.metadata().finishReason()).isEqualTo("provider_timeout");
        assertThat(answer.references()).isEmpty();
    }

    @Test
    void productGuideWorksWithoutModelAndWithoutLookingUpCurrentService() {
        var plan =
                rag.prepareStream(
                        "当前系统怎么用？",
                        5,
                        null,
                        null,
                        null,
                        "deepseek",
                        new ObservabilityContext("ops-rag-service", "PROD", "15m", null));
        assertThat(plan.immediate().metadata().retrievalMode()).isEqualTo("PRODUCT_GUIDE");
        assertThat(plan.immediate().answer()).contains("/dashboard", "/knowledge", "/automation");
        assertThat(plan.immediate().references()).isEmpty();
        assertThat(plan.immediate().metadata().budgetUsageKnown()).isTrue();
        assertThat(plan.immediate().metadata().budgetChargedTokens()).isZero();
        verifyNoInteractions(knowledge, official, platform, cmdb, evidence, prompts, invocation);
    }

    private void enableModel() {
        ai.setEnabled(true);
        var settings = new AiProperties.ProviderSettings();
        settings.setApiKey("unit-test-placeholder");
        settings.setBaseUrl("https://example.test/v1");
        settings.setModel("test-deepseek");
        ai.setProviders(Map.of("deepseek", settings));
        when(prompts.build(anyString(), any(ContextAssembler.AssembledContext.class)))
                .thenAnswer(call -> new LlmRequest("运维助手", call.getArgument(0), 300));
    }

    @Test
    void visitorGlobalQueryReservesBothPrivateAndPublicEmbeddingCalls() {
        enableModel();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(-7, "visitor", "unit-test", List.of("DEMO")),
                                null,
                                List.of()));
        String question = "什么是布隆过滤器";
        var plan = rag.prepareStream(question, 5, null);
        assertThat(plan.request().priorReservedTokens())
                .isEqualTo(2 * QueryEmbeddingBudget.reserve("布隆过滤器"));
        when(knowledge.search(anyString(), anyInt(), eq(77L)))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "trace"));
        var scoped = rag.prepareStream(question, 5, 77L);
        assertThat(scoped.immediate().metadata().budgetChargedTokens())
                .isEqualTo(QueryEmbeddingBudget.reserve(question));
        assertThat(scoped.immediate().metadata().budgetUsageKnown()).isFalse();
        clearInvocations(knowledge);
        assertThatThrownBy(() -> rag.prepareStream("甲\u0000".repeat(1000), 5, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未发送");
        verifyNoInteractions(knowledge);
    }

    @Test
    void unusedPublicCandidateIsNotPresentedAsEvidenceForGeneralAnswer() {
        enableModel();
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                8,
                                                "documentId",
                                                3,
                                                "content",
                                                "Redis连接超时先核对连接池",
                                                "documentName",
                                                "Redis故障手册",
                                                "retrievalMode",
                                                "BM25")),
                                "trace"));
        var plan = rag.prepareStream("什么是布隆过滤器", 5, null);
        when(invocation.stream(eq(plan.question()), eq(plan.request()), any(), isNull()))
                .thenReturn(
                        new LlmInvocationService.Invocation(
                                new LlmResult(
                                        "布隆过滤器是概率数据结构；[S1]未覆盖答案，以上属于通用知识。",
                                        "deepseek",
                                        "test-model",
                                        510,
                                        60),
                                12));
        var answer = rag.stream(plan, delta -> {}, null);
        assertThat(plan.sources()).isEmpty();
        assertThat(plan.request().userPrompt()).doesNotContain("Redis");
        verify(knowledge).search(eq("布隆过滤器"), anyInt(), isNull());
        assertThat(answer.references()).isEmpty();
        assertThat(answer.metadata().retrievalMode()).isEqualTo("GENERAL_AI");
        assertThat(answer.answer()).doesNotContain("[S1]");
        assertThat(answer.inputTokens()).isEqualTo(510);
    }

    @Test
    void actualPublicCitationStaysAttachedToItsSource() {
        enableModel();
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                8,
                                                "documentId",
                                                3,
                                                "content",
                                                "连接池用于复用连接",
                                                "documentName",
                                                "连接池指南",
                                                "retrievalMode",
                                                "BM25")),
                                "trace"));
        var plan = rag.prepareStream("什么是连接池？请用两句话解释。", 5, null);
        when(invocation.stream(eq(plan.question()), eq(plan.request()), any(), isNull()))
                .thenReturn(
                        new LlmInvocationService.Invocation(
                                new LlmResult("连接池用于复用连接 [S1]", "deepseek", "test-model", 30, 12),
                                8));
        var answer = rag.stream(plan, delta -> {}, null);
        assertThat(answer.references()).hasSize(1);
        assertThat(answer.references().get(0).documentId()).isEqualTo(3);
        assertThat(answer.metadata().retrievalMode()).isEqualTo("BM25");
        verify(knowledge).search(eq("连接池"), anyInt(), isNull());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void actualRedisCoverageGapReplayRemovesOnlyPublicReferences(boolean publicScope) {
        enableModel();
        ReflectionTestUtils.setField(rag, "officialKnowledge", null);
        when(knowledge.search(anyString(), anyInt(), any()))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                1002,
                                                "documentId",
                                                1001,
                                                "content",
                                                "连接池耗尽的临时措施包括限制入口流量、终止异常长事务和降低慢 SQL 并发；恢复后应补充容量评估。",
                                                "documentName",
                                                "MySQL连接池排障手册.md",
                                                "retrievalMode",
                                                "BM25")),
                                "trace"));
        String original =
                "知识库未包含Redis连接超时的排障内容，仅涉及MySQL连接池的相关资料[S1]，无法据此回答Redis问题。\n\n"
                        + "通用建议：Redis连接超时可从网络连通性（ping/端口连通测试）、客户端连接池配置（如maxTotal、maxWait）、"
                        + "服务端maxclients与超时参数（如timeout、tcp-keepalive）、慢命令或阻塞操作（如KEYS、大key）"
                        + "及系统资源（CPU、内存、文件描述符）入手排查，逐步缩小范围。";
        var plan = citationReplayPlan("Redis连接超时排查有哪些建议？请优先根据知识库用两句话回答，并标出引用。", publicScope);
        when(invocation.stream(eq(plan.question()), eq(plan.request()), any(), isNull()))
                .thenReturn(
                        new LlmInvocationService.Invocation(
                                new LlmResult(original, "deepseek", "test-model", 534, 111), 2160));
        var answer = rag.stream(plan, delta -> {}, null);
        assertThat(plan.sources()).hasSize(1);
        assertThat(plan.generalFallbackAllowed()).isEqualTo(publicScope);
        assertThat(answer.answer())
                .isEqualTo(publicScope ? original.replace("[S1]", "") : original);
        assertThat(answer.inputTokens()).isEqualTo(534);
        if (publicScope) {
            assertThat(answer.references()).isEmpty();
            assertThat(answer.metadata().retrievalMode()).isEqualTo("GENERAL_AI");
        } else {
            assertThat(answer.references()).hasSize(1);
            assertThat(answer.references().get(0).documentId()).isEqualTo(1001);
            assertThat(answer.metadata().retrievalMode()).isEqualTo("BM25");
        }
    }

    @Test
    void publicMixedAnswerKeepsSupportedSourceAndDropsCoverageOnlySource() {
        enableModel();
        when(knowledge.search(anyString(), anyInt(), eq(1001L)))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                1001,
                                                "documentId",
                                                1001,
                                                "chunkIndex",
                                                0,
                                                "content",
                                                "MySQL连接超时时先检查Hikari active指标",
                                                "documentName",
                                                "MySQL手册",
                                                "retrievalMode",
                                                "BM25"),
                                        Map.of(
                                                "chunkId",
                                                1002,
                                                "documentId",
                                                1001,
                                                "chunkIndex",
                                                1,
                                                "content",
                                                "恢复后补充容量评估",
                                                "documentName",
                                                "MySQL手册",
                                                "retrievalMode",
                                                "BM25")),
                                "trace"));
        var plan = citationReplayPlan("MySQL连接超时排查有哪些建议？", true);
        String original = "MySQL连接超时时先检查Hikari active指标[S1]。资料未提供Redis连接超时信息[S2]。通用建议：检查网络。";
        when(invocation.stream(eq(plan.question()), eq(plan.request()), any(), isNull()))
                .thenReturn(
                        new LlmInvocationService.Invocation(
                                new LlmResult(original, "deepseek", "test-model", 30, 12), 8));
        var answer = rag.stream(plan, delta -> {}, null);
        assertThat(plan.sources()).hasSize(2);
        assertThat(answer.answer()).isEqualTo(original.replace("[S2]", ""));
        assertThat(answer.references()).hasSize(1);
        assertThat(answer.references().get(0).chunkId()).isEqualTo(1001);
        assertThat(answer.metadata().retrievalMode()).isEqualTo("BM25");
    }

    @ParameterizedTest
    @ValueSource(strings = {"r7", "r8"})
    void actualCloudCoverageFailuresRemoveOnlyTheirCoverageReferences(String revision) {
        enableModel();
        when(knowledge.search(anyString(), anyInt(), eq(1001L)))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                1001,
                                                "documentId",
                                                1001,
                                                "chunkIndex",
                                                0,
                                                "content",
                                                "数据库连接超时时，先检查 Hikari active、idle、pending 指标，再核对"
                                                    + " MySQL max_connections 与慢查询。不要直接无限扩大连接池。",
                                                "documentName",
                                                "MySQL连接池排障手册.md",
                                                "retrievalMode",
                                                "BM25"),
                                        Map.of(
                                                "chunkId",
                                                1002,
                                                "documentId",
                                                1001,
                                                "chunkIndex",
                                                1,
                                                "content",
                                                "连接池耗尽的临时措施包括限制入口流量、终止异常长事务和降低慢 SQL 并发；恢复后应补充容量评估。",
                                                "documentName",
                                                "MySQL连接池排障手册.md",
                                                "retrievalMode",
                                                "BM25"),
                                        Map.of(
                                                "chunkId",
                                                1003,
                                                "documentId",
                                                1002,
                                                "chunkIndex",
                                                0,
                                                "content",
                                                "Redis 命中率下降时，核对 key 过期是否集中、淘汰策略、内存碎片率与回源"
                                                        + " QPS，避免缓存雪崩。",
                                                "documentName",
                                                "Redis与Nacos排障手册.md",
                                                "retrievalMode",
                                                "BM25")),
                                "trace"));
        String original =
                revision.equals("r7")
                        ? "知识库中未包含Redis连接超时的排查建议，先针对类似场景作说明：关于连接类超时可从连接池指标切入。"
                                + "但该条针对MySQL而非Redis；[S3]针对Redis缓存命中率下降，未涉及连接超时。\n\n"
                                + "通用建议：Redis连接超时可按链路排查——先确认客户端（连接池等待、命令超时参数）、网络（TCP握手、延迟、丢包），"
                                + "再检查服务端（maxclients、慢命令、阻塞操作、内存淘汰导致的抖动），最后看是否存在大Key或热点引起的阻塞。"
                        : "知识库中未直接覆盖 Redis 连接超时的排查步骤，可用片段 [S3] 仅涉及 Redis"
                              + " 命中率下降（key过期集中、淘汰策略、内存碎片率与回源QPS），并非连接超时问题 [S3]； 与  均针对 MySQL"
                              + " 连接池，不适用于 Redis。因此，依据给定资料无法给出针对性答案，证据不足。\n\n"
                              + "---\n\n"
                              + "**通用建议（基于通用技术知识，非知识库内容）**：Redis 连接超时可依次排查：①网络与安全组（ping/telnet"
                              + " 测试端口连通性、防火墙/云安全组规则）；②客户端连接池配置（maxTotal/maxIdle 过小、minIdle"
                              + " 不足导致频繁创建连接）；③Redis 服务端负载（`INFO clients` 查看 connected_clients 是否达"
                              + " maxclients 限制、CPU/内存占用）；④阻塞操作（如 KEYS、大 key 删除导致单线程阻塞，用 `SLOWLOG`"
                              + " 检查）；⑤超时参数设置（timeout 过短或 TCP backlog"
                              + " 不足导致队列积压）。排查时优先观测指标而非直接调参，变更需小步验证。";
        int outputTokens = revision.equals("r7") ? 122 : 257;
        var plan = citationReplayPlan("Redis连接超时排查有哪些建议？请优先根据知识库用两句话回答，并标出引用。", true);
        when(invocation.stream(eq(plan.question()), eq(plan.request()), any(), isNull()))
                .thenReturn(
                        new LlmInvocationService.Invocation(
                                new LlmResult(
                                        original, "deepseek", "test-model", 649, outputTokens),
                                2505));

        var answer = rag.stream(plan, delta -> {}, null);

        assertThat(plan.sources()).hasSize(3);
        assertThat(answer.answer())
                .isEqualTo(original.replace("[S3]", revision.equals("r7") ? "该资料" : ""));
        assertThat(answer.references()).isEmpty();
        assertThat(answer.metadata().retrievalMode()).isEqualTo("GENERAL_AI");
        assertThat(answer.inputTokens()).isEqualTo(649);
        assertThat(answer.outputTokens()).isEqualTo(outputTokens);
    }

    @Test
    void missingCitationCannotTurnAnExplicitDocumentAnswerIntoGeneralKnowledge() {
        enableModel();
        when(knowledge.search(anyString(), anyInt(), eq(77L)))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                8,
                                                "documentId",
                                                77,
                                                "content",
                                                "专属核对短语是青岚",
                                                "documentName",
                                                "本人文档",
                                                "retrievalMode",
                                                "VECTOR")),
                                "trace"));
        var plan = rag.prepareStream("文档的专属核对短语是什么", 5, 77L);
        when(invocation.stream(eq(plan.question()), eq(plan.request()), any(), isNull()))
                .thenReturn(
                        new LlmInvocationService.Invocation(
                                new LlmResult("文档的专属核对短语是青岚。", "deepseek", "test-model", 30, 10),
                                7));
        var answer = rag.stream(plan, delta -> {}, null);
        assertThat(plan.generalFallbackAllowed()).isFalse();
        assertThat(answer.references()).hasSize(1);
        assertThat(answer.metadata().retrievalMode()).isEqualTo("VECTOR");
    }

    @Test
    void redisSentinelKnowledgeDoesNotReplaceAlibabaSentinelFlowControl() {
        var fallback = new OfficialKnowledgeFallback(official);
        assertThat(fallback.topic("Redis Sentinel 如何故障转移")).isEqualTo("REDIS_SENTINEL");
        assertThat(
                        fallback.relevantKnowledge(
                                "Sentinel 限流原理",
                                List.of(
                                        RetrievedChunk.from(
                                                Map.of(
                                                        "chunkId",
                                                        1,
                                                        "documentId",
                                                        1,
                                                        "content",
                                                        "Redis Sentinel 主节点故障转移",
                                                        "documentName",
                                                        "Redis指南")))))
                .isFalse();
    }

    private RagService.StreamPlan citationReplayPlan(String question, boolean publicScope) {
        // 历史失败回答用于独立重放引用清理；不要求当前公开检索继续放入已被过滤的旧资料。
        var stored = rag.prepareStream(question, 5, 1001L);
        return new RagService.StreamPlan(
                stored.question(),
                stored.chunks(),
                stored.contextSources(),
                stored.sources(),
                stored.request(),
                stored.metadata(),
                stored.immediate(),
                stored.startedNanos(),
                stored.provider(),
                stored.fallback(),
                publicScope);
    }
}
