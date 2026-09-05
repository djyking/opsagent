package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证工单范围传递、单文档优先和无附件证据时停止生成。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RagScopeTest {
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final PlatformClient platform = mock(PlatformClient.class);
    private final LlmInvocationService llm = mock(LlmInvocationService.class);

    @Test
    void shouldSendBothDocumentAndTicketScopeAndKeepCatalogQuestionInDocument() {
        when(knowledge.searchTicket("目前有哪些服务？", 30, 1036L, 2053L)).thenReturn(new KnowledgeClient.Envelope<>(
                0, "ok", List.of(Map.of("chunkId", 91L, "documentId", 1036L,
                        "content", "文档记载：billing-service 是账单服务。", "documentName", "服务清单.md",
                        "retrievalMode", "SCOPED_DOCUMENT")), "test"));
        var answer = rag().ask("目前有哪些服务？", 5, 1036L, 2053L);
        verify(knowledge).searchTicket("目前有哪些服务？", 30, 1036L, 2053L);
        verifyNoInteractions(platform, llm);
        assertThat(answer.answer()).contains("billing-service");
        assertThat(answer.references()).hasSize(1);
        assertThat(answer.references().get(0).sourceType()).isEqualTo("KNOWLEDGE_DOCUMENT");
        assertThat(answer.references().get(0).documentId()).isEqualTo(1036L);
    }

    @Test
    void shouldNotFallBackToGlobalKnowledgeOrGenerateWhenTicketHasNoReadableAttachments() {
        when(knowledge.searchTicket("附件里写了什么？", 30, null, 2053L))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "test"));
        var answer = rag().ask("附件里写了什么？", 5, null, 2053L);
        assertThat(answer.answer()).contains("不足以确认");
        assertThat(answer.references()).isEmpty();
        verify(knowledge, never()).search(anyString(), anyInt(), any());
        verifyNoInteractions(llm, platform);
    }

    @Test
    void shouldPreserveSafeScopeErrorsWithoutExposingUpstreamMessages() {
        when(knowledge.searchTicket("附件问题", 30, 1036L, 9999L))
                .thenReturn(new KnowledgeClient.Envelope<>(40300, "db password is secret", null, "test"));
        assertThatThrownBy(() -> rag().ask("附件问题", 5, 1036L, 9999L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不可访问")
                .hasMessageNotContaining("secret");
        verifyNoInteractions(llm);
    }

    @Test
    void shouldRejectInaccessibleTicketBeforeDirectoryIntentCanReadCmdb() {
        when(knowledge.ticketDocuments(9999L))
                .thenReturn(new KnowledgeClient.Envelope<>(40300, "access denied", null, "test"));
        assertThatThrownBy(() -> rag().ask("当前服务清单", 5, null, 9999L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不可访问");
        verifyNoInteractions(platform, llm);
    }

    private RagService rag() {
        RagProperties properties = new RagProperties();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RerankService rerank = new RerankService(properties, mock(BgeRemoteRerankProvider.class),
                new NoOpRerankProvider(), metrics);
        return new RagService(knowledge, properties, new AiProperties(), mock(PromptBuilder.class), llm,
                new CitationValidator(), rerank, new ContextAssembler(properties, metrics),
                metrics, new CmdbAnswerService(platform));
    }
}
