package com.opsagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证问答严格按照检索、构建 Prompt、调用模型和生成来源的顺序执行。
 *
 * @author heyu
 * @since 2026/9/2
 */
class RagServiceTest {
    @Test
    void shouldRetrieveBeforeInvokingModelAndReturnRealSource() {
        KnowledgeClient knowledge = mock(KnowledgeClient.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        LlmInvocationService invocationService = mock(LlmInvocationService.class);
        AiProperties ai = new AiProperties();
        ai.setEnabled(true);
        configureModel(ai);
        Map<String, Object> row = Map.of(
                "chunkId", 7L,
                "documentId", 3L,
                "chunkIndex", 1,
                "content", "先检查 Sentinel 是否已完成切换。",
                "documentName", "Redis手册.md");
        when(knowledge.search("Redis 主节点挂了以后第一步做什么？", 30, null))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(row), "trace"));
        LlmRequest request = new LlmRequest("system", "user", 100);
        when(promptBuilder.build(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(ContextAssembler.AssembledContext.class)))
                .thenReturn(request);
        LlmResult result = new LlmResult("先检查切换 [S1]", "openai", "model", 10, 5);
        when(invocationService.invoke(
                        "Redis 主节点挂了以后第一步做什么？", request))
                .thenReturn(new LlmInvocationService.Invocation(result, 123));
        RagProperties properties = new RagProperties();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RagService service = new RagService(
                knowledge,
                properties,
                ai,
                promptBuilder,
                invocationService,
                new CitationValidator(),
                rerankService(properties, metrics),
                new ContextAssembler(properties, metrics),
                metrics, mock(CmdbAnswerService.class));

        RagService.Answer answer = service.ask("Redis 主节点挂了以后第一步做什么？", 5);

        InOrder order = inOrder(knowledge, promptBuilder, invocationService);
        order.verify(knowledge).search("Redis 主节点挂了以后第一步做什么？", 30, null);
        order.verify(promptBuilder).build(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(ContextAssembler.AssembledContext.class));
        order.verify(invocationService).invoke(
                "Redis 主节点挂了以后第一步做什么？", request);
        assertThat(answer.references()).extracting(RagService.Source::chunkId).containsExactly(7L);
        assertThat(answer.answer()).contains("[S1]");
    }

    @Test
    void shouldNotAskModelForMissingInternalEvidence() {
        KnowledgeClient knowledge = mock(KnowledgeClient.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        LlmInvocationService invocationService = mock(LlmInvocationService.class);
        AiProperties ai = new AiProperties();
        ai.setEnabled(true);
        RagProperties properties = new RagProperties();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RagService service = new RagService(
                knowledge,
                properties,
                ai,
                promptBuilder,
                invocationService,
                new CitationValidator(),
                rerankService(properties, metrics),
                new ContextAssembler(properties, metrics),
                metrics, mock(CmdbAnswerService.class));

        RagService.Answer answer = service.ask("OpsAgent 生产 MySQL root 密码是多少？", 5);

        assertThat(answer.answer()).contains("知识库内容不足");
        verifyNoInteractions(knowledge, promptBuilder, invocationService);
    }

    @Test
    void shouldExposeIncompleteGenerationAndPreserveItsText() {
        AiProperties ai = new AiProperties();
        ai.setEnabled(true);
        configureModel(ai);
        RagProperties properties = new RagProperties();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        LlmInvocationService invocation = mock(LlmInvocationService.class);
        LlmRequest request = new LlmRequest("system", "user", 4096);
        String partial = "## 排查步骤\n需要检查的连接参数是";
        when(invocation.stream(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new LlmInvocationService.Invocation(
                        new LlmResult(partial, "deepseek", "model", 20, 4096, false, "length", 2), 100));
        RagService service = new RagService(
                mock(KnowledgeClient.class), properties, ai, mock(PromptBuilder.class), invocation,
                new CitationValidator(), rerankService(properties, metrics),
                new ContextAssembler(properties, metrics), metrics, mock(CmdbAnswerService.class));
        RagService.StreamPlan plan = new RagService.StreamPlan(
                "问题", List.of(), List.of(), List.of(), request,
                new RagService.AnswerMetadata("NONE", false, 0, 0, 0, false, null), null, 0);

        RagService.Answer answer = service.stream(
                plan, delta -> {}, new LlmInvocationService.AuditContext(1, "test"));

        assertThat(answer.answer()).isEqualTo(partial);
        assertThat(answer.metadata().generationComplete()).isFalse();
        assertThat(answer.metadata().finishReason()).isEqualTo("length");
        assertThat(answer.metadata().continuationCount()).isEqualTo(2);
        assertThat(answer.metadata().degradedReason()).isEqualTo("LLM_INCOMPLETE");
    }

    @Test
    void shouldUseHistoryOnlyAsConversationContextAndSearchCurrentQuestion() {
        AiProperties ai = new AiProperties();
        ai.setEnabled(true);
        configureModel(ai);
        RagProperties properties = new RagProperties();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        KnowledgeClient knowledge = mock(KnowledgeClient.class);
        PromptBuilder builder = mock(PromptBuilder.class);
        String question = "连接超时如何排查？";
        when(knowledge.search(question, 30, null))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "test"));
        when(builder.build(org.mockito.ArgumentMatchers.eq(question),
                org.mockito.ArgumentMatchers.any(ContextAssembler.AssembledContext.class)))
                .thenReturn(new LlmRequest("system", "current question and evidence", 4096));
        RagService service = new RagService(
                knowledge, properties, ai, builder, mock(LlmInvocationService.class),
                new CitationValidator(), rerankService(properties, metrics),
                new ContextAssembler(properties, metrics), metrics, mock(CmdbAnswerService.class));

        RagService.StreamPlan plan = service.prepareStream(question, 5, null, "用户：旧问题\n助手：旧答案");

        org.mockito.Mockito.verify(knowledge).search(question, 30, null);
        assertThat(plan.request().systemPrompt()).contains("不是外部事实证据", "本次知识上下文");
        assertThat(plan.request().userPrompt()).contains("<conversation_history>", "旧答案");
        assertThat(plan.sources()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "false,false,LLM_DISABLED,未启用",
        "true,false,LLM_NOT_CONFIGURED,尚未配置",
        "true,true,LLM_UNAVAILABLE,调用失败"
    })
    void shouldExplainFallbackWithoutPretendingToGenerate(
            boolean enabled, boolean configured, String reason, String explanation) {
        KnowledgeClient knowledge = mock(KnowledgeClient.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        LlmInvocationService invocation = mock(LlmInvocationService.class);
        AiProperties ai = new AiProperties();
        ai.setEnabled(enabled);
        if (configured) {
            configureModel(ai);
        }
        String question = "磁盘使用率过高如何排查？";
        when(knowledge.search(question, 30, null)).thenReturn(new KnowledgeClient.Envelope<>(
                0, "ok", List.of(Map.of(
                        "chunkId", 7L, "documentId", 3L, "chunkIndex", 0,
                        "content", "先检查磁盘空间。", "documentName", "磁盘手册.md")), "trace"));
        if (configured) {
            LlmRequest request = new LlmRequest("system", "user", 100);
            when(promptBuilder.build(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(ContextAssembler.AssembledContext.class)))
                    .thenReturn(request);
            when(invocation.invoke(question, request)).thenThrow(
                    new AiProviderException("deepseek", 503, "服务暂不可用", null));
        }
        RagProperties properties = new RagProperties();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try {
            RagService service = new RagService(
                    knowledge, properties, ai, promptBuilder, invocation,
                    new CitationValidator(), rerankService(properties, metrics),
                    new ContextAssembler(properties, metrics), metrics, mock(CmdbAnswerService.class));
            RagService.Answer answer = service.ask(question, 5);
            assertThat(answer.model()).isEqualTo("retrieval-only");
            assertThat(answer.metadata().degradedReason()).isEqualTo(reason);
            assertThat(answer.answer()).contains(explanation, "未经 AI 生成", "[S1]");
            assertThat(answer.outputTokens()).isZero();
            if (!configured) {
                verifyNoInteractions(promptBuilder, invocation);
            }
        } finally {
            metrics.close();
        }
    }

    private void configureModel(AiProperties ai) {
        AiProperties.ProviderSettings settings = new AiProperties.ProviderSettings();
        settings.setApiKey("unit-test-placeholder");
        settings.setModel("test-model");
        ai.setProviders(Map.of("deepseek", settings));
    }

    private RerankService rerankService(
            RagProperties properties, SimpleMeterRegistry metrics) {
        return new RerankService(
                properties,
                new BgeRemoteRerankProvider(properties),
                new NoOpRerankProvider(),
                metrics);
    }
}
