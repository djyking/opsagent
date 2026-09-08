package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private RagService rag;

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
                        mock(PromptBuilder.class),
                        mock(LlmInvocationService.class),
                        new CitationValidator(),
                        rerank,
                        new ContextAssembler(properties, metrics),
                        metrics,
                        mock(CmdbAnswerService.class),
                        new OperationsAnswerService(platform, ai));
        ReflectionTestUtils.setField(
                rag, "officialKnowledge", new OfficialKnowledgeFallback(official));
        when(knowledge.search(anyString(), anyInt(), isNull()))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "trace"));
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
        when(official.search(anyString()))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("status", "UNAVAILABLE"));
        var plan = rag.prepareStream("RabbitMQ 资源告警的常见原因？", 5, null);
        assertThat(plan.sources()).isEmpty();
        assertThat(plan.immediate().answer()).contains("未取得可引用的网页片段");
        assertThat(plan.immediate().metadata().degradedReason())
                .isEqualTo("OFFICIAL_SOURCE_UNAVAILABLE");
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
}
