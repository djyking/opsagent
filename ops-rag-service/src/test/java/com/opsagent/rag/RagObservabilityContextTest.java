package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void streamPlanKeepsQuestionAndConversationHistoryOutsideEvidence() {
        String history = "用户：此前讨论排查方法\n助手：先确认采样。";
        var plan = rag.prepareStream(QUESTION, 5, null, null, history, "openai", context);
        assertThat(plan.question()).isEqualTo(QUESTION);
        verify(knowledge).search(QUESTION, 30, null);
        String user = plan.request().userPrompt();
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
    void conversationAndStandaloneControllersPassTheSameReferenceWithoutAppendingItToQuestion() {
        var plans = mock(RagService.class);
        var conversations = mock(RagConversationService.class);
        var streaming = mock(RagStreamingService.class);
        var limiter = mock(RagRateLimiter.class);
        var scope = mock(RagRateLimiter.Scope.class);
        when(limiter.requestScope()).thenReturn(scope);
        when(conversations.begin("conversation", 7, QUESTION)).thenReturn(12L);
        when(conversations.context("conversation", 7)).thenReturn("history-only");
        when(streaming.open(any(), any(), any(), any())).thenReturn(new SseEmitter());
        var controller = new RagConversationController(conversations, plans, streaming, limiter);
        controller.ask(
                "conversation",
                new RagConversationController.QuestionRequest(
                        QUESTION, 5, null, null, "openai", context));
        verify(conversations).begin("conversation", 7, QUESTION);
        verify(plans).prepareStream(QUESTION, 5, null, null, "history-only", "openai", context);
        var standalone = new RagController(plans, limiter, streaming);
        var request = new RagController.ChatRequest(QUESTION, 5, null, null, "openai", context);
        standalone.stream(request);
        verify(plans).prepareStream(QUESTION, 5, null, null, null, "openai", context);
        standalone.chat(request);
        verify(plans).ask(QUESTION, 5, null, null, "openai", context);
    }

    @Test
    void standaloneStreamDoesNotTurnEvidenceForbiddenIntoHttpSuccessErrorSse() {
        var streaming = mock(RagStreamingService.class);
        var limiter = mock(RagRateLimiter.class);
        var scope = mock(RagRateLimiter.Scope.class);
        when(limiter.requestScope()).thenReturn(scope);
        var forbidden = new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权读取证据");
        when(evidence.load(context)).thenThrow(forbidden);
        var controller = new RagController(rag, limiter, streaming);
        assertThatThrownBy(
                        () ->
                                controller.stream(
                                        new RagController.ChatRequest(
                                                QUESTION, 5, null, null, "openai", context)))
                .isSameAs(forbidden);
        verify(streaming, never()).error(anyString());
        verify(scope).failure(forbidden);
        verifyNoInteractions(knowledge, invocation);
    }
}
