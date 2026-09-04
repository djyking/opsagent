package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证查询规范化、权限前置过滤、业务标识提取和应用层 RRF 融合。
 *
 * @author heyu
 * @since 2026/9/3
 */
class HybridRetrievalTest {

    @Test
    void shouldNormalizeUnicodeAndWhitespaceWithoutRemovingIdentifiers() {
        QueryNormalizer normalizer = new QueryNormalizer();

        String result = normalizer.normalize("  ＯＰＳ-SCENE-1007\n  BlockException  ");

        assertThat(result).isEqualTo("OPS-SCENE-1007 BlockException");
    }

    @Test
    void shouldBuildMandatoryPermissionFiltersBeforeSearch() {
        ElasticsearchVectorStore store = new ElasticsearchVectorStore(
                new VectorProperties(), new ObjectMapper());
        RetrievalRequest request = request(5);

        List<Map<String, Object>> filters = store.mandatoryFilters(request);

        assertThat(filters.toString())
                .contains("PUBLISHED", "documentId=9", "knowledgeBaseId=3")
                .contains("knowledgeBaseId=[", "3", "4", "PUBLIC", "createBy=7");
        assertThat(store.exactIdentifiers("OPS-SCENE-1007 order.cache.ttl.strategy 429"))
                .containsExactly("OPS-SCENE-1007", "order.cache.ttl.strategy", "429");
    }

    @Test
    void shouldFuseBm25AndVectorRanksWithoutAddingRawScores() {
        VectorProperties properties = new VectorProperties();
        properties.setRrfRankConstant(60);
        KnowledgeIndexService service = new KnowledgeIndexService(
                properties,
                mock(EmbeddingClient.class),
                mock(ElasticsearchVectorStore.class),
                mock(KnowledgeRepository.class),
                new ObjectMapper(),
                new ApproxTokenCounter(),
                new QueryNormalizer(),
                new SimpleMeterRegistry());
        List<ElasticsearchVectorStore.SearchHit> bm25 = List.of(
                hit("1:1:0", 10, 10.0D),
                hit("1:1:1", 11, 5.0D));
        List<ElasticsearchVectorStore.SearchHit> vector = List.of(
                hit("1:1:1", 11, 0.90D),
                hit("1:1:2", 12, 0.85D));

        List<RetrievedChunk> result = service.fuse(bm25, vector, request(3), null);

        assertThat(result).extracting(RetrievedChunk::chunkId)
                .containsExactly(11L, 10L, 12L);
        assertThat(result.get(0).channels())
                .containsExactlyInAnyOrder(RetrievalChannel.BM25, RetrievalChannel.VECTOR);
        assertThat(result.get(0).bm25Score()).isEqualTo(5.0D);
        assertThat(result.get(0).vectorScore()).isEqualTo(0.90D);
        assertThat(result.get(0).rrfScore()).isCloseTo(
                1.0D / 62 + 1.0D / 61,
                org.assertj.core.data.Offset.offset(0.0000001D));
    }

    private RetrievalRequest request(int resultSize) {
        return new RetrievalRequest(
                "Redis error 429", 3L, 9L, null, null, Set.of(3L, 4L),
                false, 7L, false, resultSize);
    }

    private ElasticsearchVectorStore.SearchHit hit(String id, long chunkId, double score) {
        return new ElasticsearchVectorStore.SearchHit(id, score, Map.of(
                "chunkId", chunkId,
                "documentId", 1L,
                "chunkIndex", (int) chunkId,
                "documentName", "Redis SOP",
                "headingPath", List.of("告警", "排查"),
                "content", "缓存命中率下降",
                "pageStart", 1,
                "pageEnd", 1));
    }
}
