package com.opsagent.rag;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证可选 BGE 重排、超时降级、邻居扩展、去重、文档配额和 Token Budget。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RerankAndContextTest {

    @Test
    void shouldKeepRrfOrderWhenRerankIsDisabled() {
        List<RerankDocument> candidates = List.of(
                document(0, 10), document(1, 11), document(2, 12));

        List<RerankResult> result = new NoOpRerankProvider()
                .rerank("Redis", candidates, 2);

        assertThat(result).extracting(RerankResult::candidateIndex).containsExactly(0, 1);
    }

    @Test
    void shouldAlignRemoteResultsByCandidateIndexAndTopN() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("{\"results\":[{\"index\":1,\"score\":0.95},"
                        + "{\"index\":0,\"score\":0.82}]}"));
        server.start();
        try {
            RagProperties properties = properties(server, 3);
            List<RerankResult> result = new BgeRemoteRerankProvider(properties)
                    .rerank("Redis", List.of(document(0, 10), document(1, 11)), 2);

            assertThat(result).extracting(RerankResult::candidateIndex).containsExactly(1, 0);
            assertThat(result).extracting(RerankResult::score).containsExactly(0.95D, 0.82D);
        } finally {
            server.shutdown();
        }
    }

    @Test
    void shouldFallbackToRrfOrderWhenRemoteTimesOut() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("{\"results\":[{\"index\":1,\"score\":0.95}]}")
                .setBodyDelay(1500, TimeUnit.MILLISECONDS));
        server.start();
        try {
            RagProperties properties = properties(server, 1);
            RerankService service = new RerankService(
                    properties,
                    new BgeRemoteRerankProvider(properties),
                    new NoOpRerankProvider(),
                    new SimpleMeterRegistry());

            RerankService.Outcome outcome = service.rerank(
                    "Redis", List.of(chunk(10, 1, 0, "主片段"), chunk(11, 1, 1, "邻居")), 2);

            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.degradedReason()).isEqualTo("REMOTE_ERROR");
            assertThat(outcome.chunks()).extracting(RetrievedChunk::chunkId)
                    .containsExactly(10L, 11L);
        } finally {
            server.shutdown();
        }
    }

    @Test
    void shouldExpandNeighborsSuppressDuplicatesAndEnforceDocumentLimit() {
        RagProperties properties = new RagProperties();
        properties.setNeighborWindow(1);
        properties.setMaxChunksPerDocument(2);
        properties.setMaxContextTokens(6000);
        ContextAssembler assembler = new ContextAssembler(properties, new SimpleMeterRegistry());
        RetrievedChunk main = chunk(11, 1, 1, "Redis 故障主片段");
        RetrievedChunk neighbor = chunk(10, 1, 0, "Redis 故障前置检查");
        RetrievedChunk duplicate = chunk(12, 1, 2, "Redis 故障主片段");
        RetrievedChunk other = chunk(20, 2, 0, "RabbitMQ 排查步骤");

        ContextAssembler.AssembledContext context = assembler.assemble(
                List.of(main, other), List.of(neighbor, main, duplicate, other), false);

        assertThat(context.sources()).extracting(ContextAssembler.ContextSource::sourceId)
                .containsExactly("S1", "S2", "S3");
        assertThat(context.sources()).extracting(source -> source.chunk().chunkId())
                .containsExactly(11L, 10L, 20L);
        assertThat(context.text()).contains("[S1]", "[S2]", "[S3]");
    }

    @Test
    void shouldStopBeforeExceedingTokenBudget() {
        RagProperties properties = new RagProperties();
        properties.setNeighborWindow(0);
        properties.setMaxContextTokens(100);
        ContextAssembler assembler = new ContextAssembler(properties, new SimpleMeterRegistry());
        RetrievedChunk first = chunk(10, 1, 0, "故".repeat(40));
        RetrievedChunk second = chunk(20, 2, 0, "障".repeat(40));

        ContextAssembler.AssembledContext context = assembler.assemble(
                List.of(first, second), List.of(first, second), false);

        assertThat(context.sources()).hasSize(1);
        assertThat(context.tokenCount()).isLessThanOrEqualTo(100);
    }

    private RerankDocument document(int index, long chunkId) {
        return new RerankDocument(index, chunkId, "标题", "章节", "正文", null);
    }

    private RetrievedChunk chunk(long id, long documentId, int index, String content) {
        return new RetrievedChunk(
                id,
                documentId,
                index,
                content,
                "Redis SOP",
                "排查 > Redis",
                1,
                1,
                1,
                "2026-09-03T10:00:00",
                1.0D,
                0.9D,
                0.03D,
                null,
                Set.of("BM25", "VECTOR"),
                "HYBRID_RRF",
                "");
    }

    private RagProperties properties(MockWebServer server, int timeoutSeconds) {
        RagProperties properties = new RagProperties();
        properties.setRerankEnabled(true);
        properties.setRerankBaseUrl(server.url("/").toString());
        properties.setRerankTimeoutSeconds(timeoutSeconds);
        return properties;
    }
}
