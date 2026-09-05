package com.opsagent.rag;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证会话指代追问使用正确主题检索，不让无关资料或错误概括改写原始对话。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RagFollowupRetrievalTest {
    private static final String REDIS_QUESTION = "请给出 Redis 连接超时的完整排查方案，包含第1步前置检查。";
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final PromptBuilder prompts = mock(PromptBuilder.class);
    private final RerankService rerank = mock(RerankService.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private RagService service;

    @BeforeEach
    void setup() {
        AiProperties ai = new AiProperties();
        ai.setEnabled(true);
        AiProperties.ProviderSettings model = new AiProperties.ProviderSettings();
        model.setApiKey("unit-test-key");
        model.setModel("unit-test-model");
        ai.setProviders(java.util.Map.of("deepseek", model));
        RagProperties properties = new RagProperties();
        when(knowledge.search(anyString(), anyInt(), nullable(Long.class)))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "test"));
        when(rerank.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(new RerankService.Outcome(List.of(), false, null));
        when(prompts.build(anyString(), any(ContextAssembler.AssembledContext.class)))
                .thenAnswer(call -> new LlmRequest("system", "当前问题：" + call.getArgument(0), 4096));
        service = new RagService(knowledge, properties, ai, prompts,
                mock(LlmInvocationService.class), new CitationValidator(), rerank,
                new ContextAssembler(properties, metrics), metrics, mock(CmdbAnswerService.class));
    }

    @AfterEach
    void close() { metrics.close(); }

    @ParameterizedTest
    @ValueSource(strings = {
        "你刚才给出的是哪一种组件的连接超时排查方案？用一句话回答。",
        "上述方案怎么验证？",
        "上面的方法还有哪些注意事项？",
        "继续补充排查方法。",
        "第2步应该如何执行？",
        "它为什么会发生连接超时？"
    })
    void shouldAnchorReferencesInTheLastIndependentQuestion(String question) {
        when(knowledge.search(anyString(), anyInt(), eq(9L)))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(java.util.Map.of(
                        "chunkId", 1L, "documentId", 9L, "content", "Redis 连接超时排查")), "test"));
        RagService.StreamPlan plan = service.prepareStream(question, 5, 9L, history());

        String expected = REDIS_QUESTION + "\n" + question;
        verify(knowledge).search(expected, 30, 9L);
        verify(rerank).rerank(eq(expected), anyList(), eq(5));
        verify(prompts).build(eq(question), any(ContextAssembler.AssembledContext.class));
        assertThat(plan.question()).isEqualTo(question);
        assertThat(plan.sources()).isEmpty();
        assertThat(plan.request().systemPrompt()).contains("不能改写实际对话记录", "不是外部事实证据");
        assertThat(plan.request().userPrompt()).endsWith(question);
    }

    @Test
    void shouldResolveRepeatedReferencesPastAnIncorrectIntermediateSummary() {
        String followup = "你刚才给出的是哪一种组件的连接超时排查方案？用一句话回答。";
        String repeated = history() + "用户：" + followup + "\n助手：主要是 MySQL。\n\n";

        RagService.StreamPlan plan = service.prepareStream(followup, 5, null, repeated);

        verify(knowledge).search(REDIS_QUESTION + "\n" + followup, 30, null);
        assertThat(plan.request().systemPrompt()).contains("原始用户问题和方案正文", "应更正该概括");
    }

    @Test
    void shouldLetAnIndependentNewTopicReplaceTheOldAnchor() {
        String nextTopic = "RabbitMQ 消息堆积如何排查？";
        service.prepareStream(nextTopic, 5, null, history());
        verify(knowledge).search(nextTopic, 30, null);
        verify(rerank).rerank(eq(nextTopic), anyList(), eq(5));
    }

    @Test
    void shouldLeaveFirstTurnWithoutHistoryUnchanged() {
        String question = "第2步应该如何执行？";
        service.prepareStream(question, 5, null, null);
        verify(knowledge).search(question, 30, null);
    }

    @Test
    void shouldNotUseAssistantOnlyTextAsAQuestionAnchor() {
        String question = "你刚才说了什么？";
        service.prepareStream(question, 5, null, "助手：MySQL 连接超时。");
        verify(knowledge).search(question, 30, null);
    }

    private String history() {
        return "用户：MySQL 主从延迟怎么排查？\n助手：先检查复制状态。\n\n"
                + "用户：" + REDIS_QUESTION + "\n助手：这是 Redis 连接超时的完整排查方案。\n\n";
    }
}

