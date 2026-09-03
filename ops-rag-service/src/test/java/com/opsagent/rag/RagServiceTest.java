package com.opsagent.rag;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
        Map<String, Object> row = Map.of(
                "chunkId", 7L,
                "documentId", 3L,
                "chunkIndex", 1,
                "content", "先检查 Sentinel 是否已完成切换。",
                "documentName", "Redis手册.md");
        when(knowledge.search("Redis 主节点挂了以后第一步做什么？", 5, null))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(row), "trace"));
        LlmRequest request = new LlmRequest("system", "user", 100);
        when(promptBuilder.build(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(request);
        LlmResult result = new LlmResult("先检查切换 [chunk:7]", "openai", "model", 10, 5);
        when(invocationService.invoke(
                        "Redis 主节点挂了以后第一步做什么？", request))
                .thenReturn(new LlmInvocationService.Invocation(result, 123));
        RagService service = new RagService(
                knowledge,
                new RagProperties(),
                ai,
                promptBuilder,
                invocationService,
                new CitationValidator());

        RagService.Answer answer = service.ask("Redis 主节点挂了以后第一步做什么？", 5);

        InOrder order = inOrder(knowledge, promptBuilder, invocationService);
        order.verify(knowledge).search("Redis 主节点挂了以后第一步做什么？", 5, null);
        order.verify(promptBuilder).build(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList());
        order.verify(invocationService).invoke(
                "Redis 主节点挂了以后第一步做什么？", request);
        assertThat(answer.references()).extracting(RagService.Source::chunkId).containsExactly(7L);
        assertThat(answer.answer()).contains("[chunk:7]");
    }

    @Test
    void shouldNotAskModelForMissingInternalEvidence() {
        KnowledgeClient knowledge = mock(KnowledgeClient.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        LlmInvocationService invocationService = mock(LlmInvocationService.class);
        AiProperties ai = new AiProperties();
        ai.setEnabled(true);
        RagService service = new RagService(
                knowledge,
                new RagProperties(),
                ai,
                promptBuilder,
                invocationService,
                new CitationValidator());

        RagService.Answer answer = service.ask("OpsAgent 生产 MySQL root 密码是多少？", 5);

        assertThat(answer.answer()).contains("知识库内容不足");
        verifyNoInteractions(knowledge, promptBuilder, invocationService);
    }
}
