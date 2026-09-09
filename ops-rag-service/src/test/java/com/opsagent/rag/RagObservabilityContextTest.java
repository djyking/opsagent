package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 覆盖真实 RAG 编排与三个控制器入口，保护检索词、历史、提供方与引用校验。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RagObservabilityContextTest {
    private static final String QUESTION = "当前服务变慢应如何排查？";
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final LlmInvocationService invocation = mock(LlmInvocationService.class);
    private final ObservabilityEvidenceClient evidence = mock(ObservabilityEvidenceClient.class);
    private final CmdbAnswerService cmdb = mock(CmdbAnswerService.class);
    private final OperationsAnswerService operations = mock(OperationsAnswerService.class);
    private final ObservabilityContext context =
            new ObservabilityContext("ops-rag-service", "PROD", "15m", null);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private RagService rag;

    @BeforeEach
    void setup() {
        var actor = new OpsPrincipal(7, "operator", "test", List.of("OPS"));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        var ai = AiProviderSelectionTest.configured();
        var props = new RagProperties();
        var builder = mock(PromptBuilder.class);
        when(builder.build(anyString(), any(ContextAssembler.AssembledContext.class)))
                .thenAnswer(
                        call ->
                                new LlmRequest(
                                        "知识来源只能支持通用建议", "原始问题：" + call.getArgument(0), 1024));
        var rerank = mock(RerankService.class);
        when(rerank.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(call -> new RerankService.Outcome(call.getArgument(1), false, null));
        rag =
                new RagService(
                        knowledge,
                        props,
                        ai,
                        builder,
                        invocation,
                        new CitationValidator(),
                        rerank,
                        new ContextAssembler(props, metrics),
                        metrics,
                        cmdb,
                        operations);
        ReflectionTestUtils.setField(rag, "observationEvidence", evidence);
        when(knowledge.search(QUESTION, 30, null))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                7,
                                                "documentId",
                                                3,
                                                "chunkIndex",
                                                0,
                                                "content",
                                                "一般诊断步骤：确认错误率与实际探针。",
                                                "documentName",
                                                "运维指南")),
                                "trace"));
        when(evidence.load(context))
                .thenReturn(
                        new ObservabilityEvidenceClient.Evidence(
                                context,
                                UUID.randomUUID().toString(),
                                Instant.now().toString(),
                                "PARTIAL",
                                List.of("TRACE_MISSING"),
                                List.of(
                                        new ObservabilityEvidenceClient.Entry(
                                                "prometheus:rag",
                                                "PROMETHEUS",
                                                Instant.now().minusSeconds(10).toString(),
                                                "PARTIAL",
                                                "业务证据不足",
                                                JsonNodeFactory.instance
                                                        .objectNode()
                                                        .put("health", "UNKNOWN"))),
                                true));
    }

    @AfterEach
    void cleanup() {
        metrics.close();
        SecurityContextHolder.clearContext();
    }

    @Test
    void actualRetrievalProviderAndCitationValidationRemainWhileEvidenceIsSeparate() {
        when(invocation.invoke(eq("openai"), eq(QUESTION), any()))
                .thenAnswer(
                        call -> {
                            LlmRequest request = call.getArgument(2);
                            assertThat(request.userPrompt())
                                    .startsWith("原始问题：" + QUESTION)
                                    .contains(
                                            "UNTRUSTED EVIDENCE",
                                            "prometheus:rag",
                                            "TRACE_MISSING");
                            assertThat(request.systemPrompt()).contains("不可信数据", "不能填补缺失的业务事实");
                            return new LlmInvocationService.Invocation(
                                    new LlmResult(
                                            "建议 [S1]，证据 [S2]，虚构 [S999]",
                                            "openai",
                                            "chosen-model",
                                            50,
                                            20),
                                    12);
                        });
        var answer = rag.ask(QUESTION, 5, null, null, "openai", context);
        verify(knowledge).search(QUESTION, 30, null);
        verifyNoInteractions(cmdb, operations);
        assertThat(answer.provider()).isEqualTo("openai");
        assertThat(answer.answer()).contains("[S1]", "[S2]", "无效引用已移除").doesNotContain("S999");
        assertThat(answer.references()).hasSize(2);
        assertThat(answer.references().get(1).evidenceId()).isEqualTo("prometheus:rag");
        assertThat(answer.metadata().retrievalMode()).isEqualTo("OBSERVABILITY_RAG");
        assertThat(answer.metadata().degradedReason()).isEqualTo("OBSERVABILITY_PARTIAL");
    }

    @Test
    void fullGatewayObservationPromptAboveOldLimitFitsFiftyThousandBudget() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(-7, "visitor", "test", List.of("DEMO")),
                                null,
                                List.of()));
        String question =
                "请分析当前服务的异常现象、证据和可能原因，并说明还需要核对哪些信息。\n\n"
                        + "当前页面上下文：ops-gateway · ALL ·"
                        + " 15m。请针对当前对象结合可读取的实时数据与知识来源进行只读分析，区分事实、推断及证据缺口；无权限或无数据时请明确说明。";
        var gateway = new ObservabilityContext("ops-gateway", "PROD", "15m", null);
        var ai = AiProviderSelectionTest.configured();
        ReflectionTestUtils.setField(
                rag,
                "promptBuilder",
                new PromptBuilder(new PromptTemplateLoader(), new RagProperties(), ai));
        when(knowledge.search(anyString(), anyInt(), eq(null)))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "test"));
        when(evidence.load(gateway))
                .thenReturn(
                        new ObservabilityEvidenceClient.Evidence(
                                gateway,
                                UUID.randomUUID().toString(),
                                Instant.now().toString(),
                                "PARTIAL",
                                List.of("TRACE_MISSING"),
                                List.of(
                                        new ObservabilityEvidenceClient.Entry(
                                                "gateway-metrics",
                                                "PROMETHEUS",
                                                Instant.now().toString(),
                                                "PARTIAL",
                                                "API网关实时观测",
                                                JsonNodeFactory.instance
                                                        .objectNode()
                                                        .put("details", "核对现场指标。".repeat(550)))),
                                true));

        var plan = rag.prepareStream(question, 5, null, null, null, "openai", gateway);

        int bound =
                AssistantTokenBudget.promptUpperBound(plan.request())
                        + plan.request().priorReservedTokens()
                        + 2048;
        assertThat(bound).isGreaterThan(10_000).isLessThanOrEqualTo(50_000);
        assertThat(plan.immediate()).isNull();
        assertThat(plan.request().userPrompt()).contains("ops-gateway", "UNTRUSTED EVIDENCE");
        try (var budget = AssistantTokenBudget.open(plan.request().priorReservedTokens(), null)) {
            var reserved =
                    AssistantTokenBudget.reserve(
                            "openai",
                            Map.of(
                                    "max_tokens",
                                    2048,
                                    "messages",
                                    List.of(
                                            Map.of(
                                                    "role",
                                                    "system",
                                                    "content",
                                                    plan.request().systemPrompt()),
                                            Map.of(
                                                    "role",
                                                    "user",
                                                    "content",
                                                    plan.request().userPrompt()))));
            assertThat(reserved.body().get("max_tokens")).isEqualTo(2048);
            assertThat(budget.attempts()).isEqualTo(1);
        }
    }

    @Test
    void observationFailureRetainsProviderAndBudgetInsteadOfZeroReadonlyIdentity() {
        var plan = rag.prepareStream(QUESTION, 5, null, null, null, "openai", context);
        var failure =
                new AiProviderException(
                        "openai", 0, "模型响应超时", null, AiProviderException.FailureKind.TIMEOUT);
        try (var budget = AssistantTokenBudget.open(5046, null)) {
            AssistantTokenBudget.reserve(
                            "openai", Map.of("max_tokens", 2048, "messages", List.of()))
                    .finish(null);
            failure.recordInvocation("chosen-model", 1234, budget);
        }
        when(invocation.stream(eq("openai"), eq(QUESTION), eq(plan.request()), any(), any()))
                .thenThrow(failure);

        var answer = rag.stream(plan, delta -> {}, null);

        assertThat(answer.provider()).isEqualTo("openai");
        assertThat(answer.model()).isEqualTo("chosen-model");
        assertThat(answer.latencyMs()).isEqualTo(1234);
        assertThat(answer.metadata().budgetLimit()).isEqualTo(50000);
        assertThat(answer.metadata().requestAttempts()).isEqualTo(1);
        assertThat(answer.metadata().budgetChargedTokens()).isGreaterThan(5046);
        assertThat(answer.metadata().budgetUsageKnown()).isFalse();
        assertThat(answer.metadata().finishReason()).isEqualTo("provider_timeout");
    }

    @Test
    void observedScopeDoesNotApplyPublicProductOrTopicFilter() {
        String question = "当前 Redis 连接超时应如何排查？";
        when(knowledge.search(question, 30, null))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                7,
                                                "documentId",
                                                3,
                                                "content",
                                                "MySQL 命中率与所选现场证据的关联记录。",
                                                "documentName",
                                                "现场记录")),
                                "trace"));

        var plan = rag.prepareStream(question, 5, null, null, null, "openai", context);

        assertThat(plan.generalFallbackAllowed()).isFalse();
        assertThat(plan.sources()).hasSize(2);
        assertThat(plan.chunks())
                .extracting(RetrievedChunk::content)
                .containsExactly("MySQL 命中率与所选现场证据的关联记录。");
    }

    @Test
    void streamPlanKeepsQuestionAndConversationHistoryOutsideEvidence() {
        String history = "用户：此前讨论排查方法\n助手：先确认采样。";
        var plan = rag.prepareStream(QUESTION, 5, null, null, history, "openai", context);
        assertThat(plan.question()).isEqualTo(QUESTION);
        assertThat(plan.request().systemPrompt())
                .contains(
                        "首句和总结也必须受本次证据覆盖约束",
                        "UNKNOWN、未观测、未返回或未授权都不等于健康或不存在",
                        "某类列表为空只能说明本次未返回该类记录",
                        "隔离演练记录为 0 不能推出无生产事故、告警、发布、配置变更或审批记录");
        assertThat(plan.request().systemPrompt()).doesNotContain("检索候选不自动等于答案证据", "应继续提供有用的通用技术知识");
        verify(knowledge).search(QUESTION, 30, null);
        String user = plan.request().userPrompt();
        assertThat(plan.sources()).hasSize(2);
        assertThat(
                        AssistantTokenBudget.promptUpperBound(plan.request())
                                + plan.request().priorReservedTokens()
                                + 2048)
                .isLessThanOrEqualTo(AssistantTokenBudget.LIMIT);
        assertThat(
                        user.substring(
                                user.indexOf("<conversation_history>"),
                                user.indexOf("</conversation_history>")))
                .contains(history)
                .doesNotContain("prometheus:rag", "TRACE_MISSING");
        when(invocation.stream(eq("openai"), eq(QUESTION), eq(plan.request()), any(), any()))
                .thenReturn(
                        new LlmInvocationService.Invocation(
                                new LlmResult("观测证据不足 [S2]", "openai", "model", 10, 5), 10));
        var answer =
                rag.stream(plan, delta -> {}, new LlmInvocationService.AuditContext(7, "operator"));
        assertThat(answer.answer()).contains("[S2]");
        assertThat(answer.references().get(1).evidenceBundleId()).isNotNull();
    }

    @Test
    void unavailableEvidenceDoesNotTriggerModelOrUseKnowledgeAsLiveFacts() {
        when(evidence.load(context))
                .thenReturn(
                        ObservabilityEvidenceClient.Evidence.unavailable(context, "SOURCE_FAILED"));
        var answer = rag.ask(QUESTION, 5, null, null, "openai", context);
        assertThat(answer.answer()).contains("不能确认当前健康或根因", "SOURCE_FAILED");
        assertThat(answer.metadata().degradedReason()).isEqualTo("OBSERVABILITY_UNAVAILABLE");
        assertThat(answer.references()).isEmpty();
        verifyNoInteractions(knowledge, invocation, cmdb, operations);
    }

    @Test
    void omittedCitationDoesNotConvertLiveEvidenceIntoGeneralAnswer() {
        var plan = rag.prepareStream(QUESTION, 5, null, null, null, "openai", context);
        when(invocation.stream(eq("openai"), eq(QUESTION), any(), any(), any()))
                .thenReturn(
                        new LlmInvocationService.Invocation(
                                new LlmResult("业务证据缺失，不能确认当前健康。", "openai", "model", 10, 5), 10));
        var answer =
                rag.stream(plan, delta -> {}, new LlmInvocationService.AuditContext(7, "operator"));
        assertThat(plan.generalFallbackAllowed()).isFalse();
        assertThat(answer.metadata().retrievalMode()).isEqualTo("OBSERVABILITY_RAG");
        assertThat(answer.references()).hasSize(2);
        assertThat(answer.answer()).contains("不能确认当前健康");
    }

    @Test
    void conversationAndStandaloneControllersPassTheSameReferenceWithoutAppendingItToQuestion() {
        var plans = mock(RagService.class);
        var conversations = mock(RagConversationService.class);
        var streaming = mock(RagStreamingService.class);
        var limiter = mock(RagRateLimiter.class);
        var scope = mock(RagRateLimiter.Scope.class);
        when(limiter.requestScope()).thenReturn(scope);
        when(conversations.begin("conversation", 7, QUESTION)).thenReturn(12L);
        when(conversations.context("conversation", 7)).thenReturn("history-only");
        org.mockito.Mockito.doAnswer(
                        call -> {
                            java.util.function.Function<
                                            java.util.function.Consumer<String>,
                                            RagService.StreamPlan>
                                    preparation = call.getArgument(0);
                            preparation.apply(phase -> {});
                            return new SseEmitter();
                        })
                .when(streaming)
                .openPrepared(any(), any(), any(), any(), any());
        var controller = new RagConversationController(conversations, plans, streaming, limiter);
        controller.ask(
                "conversation",
                new RagConversationController.QuestionRequest(
                        QUESTION, 5, null, null, "openai", context));
        verify(conversations).begin("conversation", 7, QUESTION);
        verify(plans)
                .prepareStream(
                        eq(QUESTION),
                        eq(5),
                        eq(null),
                        eq(null),
                        eq("history-only"),
                        eq("openai"),
                        eq(context),
                        eq(null),
                        any());
        var standalone = new RagController(plans, limiter, streaming);
        var request = new RagController.ChatRequest(QUESTION, 5, null, null, "openai", context);
        standalone.stream(request);
        verify(plans)
                .prepareStream(
                        eq(QUESTION),
                        eq(5),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq("openai"),
                        eq(context),
                        eq(null),
                        any());
        standalone.chat(request);
        verify(plans).ask(QUESTION, 5, null, null, "openai", context);
    }

    @Test
    void standaloneStreamReportsDownstreamForbiddenCodeWithoutModelFallback() throws Exception {
        var streaming = new RagStreamingService(rag, new AiProperties(), Runnable::run);
        var limiter = mock(RagRateLimiter.class);
        var scope = mock(RagRateLimiter.Scope.class);
        when(limiter.requestScope()).thenReturn(scope);
        var forbidden = new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权读取证据");
        when(evidence.load(context)).thenThrow(forbidden);
        var controller = new RagController(rag, limiter, streaming);
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new RagStreamingServiceTest.StreamController(
                                        () ->
                                                controller.stream(
                                                        new RagController.ChatRequest(
                                                                QUESTION, 5, null, null, "openai",
                                                                context))))
                        .build();
        var started =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/test-stream"))
                        .andReturn();
        String body =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .asyncDispatch(started))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body)
                .contains("event:error", "\"code\":40300")
                .doesNotContain("event:token", "event:done");
        verify(scope).failure(any());
        verifyNoInteractions(knowledge);
        verify(invocation).currentContext();
        org.mockito.Mockito.verifyNoMoreInteractions(invocation);
    }

    @Test
    void bothAnswerStylesPreserveEvidenceBoundaryAndFiftyThousandTokenBudget() {
        var concise =
                rag.prepareStream(
                        QUESTION,
                        5,
                        null,
                        null,
                        null,
                        "openai",
                        context,
                        AnswerStyle.CONCISE,
                        phase -> {});
        var detailed =
                rag.prepareStream(
                        QUESTION,
                        5,
                        null,
                        null,
                        null,
                        "openai",
                        context,
                        AnswerStyle.DETAILED,
                        phase -> {});
        assertThat(concise.request().systemPrompt()).contains("精简回答", "已知事实", "合理推断", "证据缺口");
        assertThat(detailed.request().systemPrompt()).contains("深入分析", "已知事实", "合理推断", "证据缺口");
        assertThat(concise.sources())
                .usingRecursiveComparison()
                .ignoringFields("sourceRetrievedAt")
                .isEqualTo(detailed.sources());
        assertThat(concise.request().priorReservedTokens())
                .isEqualTo(detailed.request().priorReservedTokens());
        assertThat(concise.request().maxOutputTokens())
                .isEqualTo(detailed.request().maxOutputTokens());
        assertThat(AssistantTokenBudget.LIMIT).isEqualTo(50000);
    }
}
