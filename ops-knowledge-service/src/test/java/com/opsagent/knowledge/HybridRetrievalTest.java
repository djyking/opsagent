package com.opsagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
        VectorProperties properties = new VectorProperties();
        ElasticsearchVectorStore store =
                new ElasticsearchVectorStore(properties, new ObjectMapper());
        QdrantVectorStore qdrantStore = new QdrantVectorStore(properties, new ObjectMapper());
        RetrievalRequest request = request(5);

        List<Map<String, Object>> filters = store.mandatoryFilters(request);
        Map<String, Object> vectorFilters = qdrantStore.mandatoryFilters(request);

        assertThat(filters.toString())
                .contains("PUBLISHED", "documentId=9", "knowledgeBaseId=3")
                .contains("knowledgeBaseId=[", "3", "4", "PUBLIC", "createBy=7");
        assertThat(vectorFilters.toString())
                .contains("PUBLISHED", "documentId", "knowledgeBaseId")
                .contains("any", "PUBLIC", "createBy");
        assertThat(store.exactIdentifiers("OPS-SCENE-1007 order.cache.ttl.strategy 429"))
                .containsExactly("OPS-SCENE-1007", "order.cache.ttl.strategy", "429");
    }

    @Test
    void shouldFuseBm25AndVectorRanksWithoutAddingRawScores() {
        VectorProperties properties = new VectorProperties();
        properties.setRrfRankConstant(60);
        KnowledgeIndexService service =
                new KnowledgeIndexService(
                        properties,
                        mock(EmbeddingClient.class),
                        mock(ElasticsearchVectorStore.class),
                        mock(QdrantVectorStore.class),
                        mock(KnowledgeRepository.class),
                        new ObjectMapper(),
                        new ApproxTokenCounter(),
                        new QueryNormalizer(),
                        new SimpleMeterRegistry());
        List<RetrievalHit> bm25 = List.of(hit("1:1:0", 10, 10.0D), hit("1:1:1", 11, 5.0D));
        List<RetrievalHit> vector = List.of(hit("11", 11, 0.90D), hit("12", 12, 0.85D));

        List<RetrievedChunk> result = service.fuse(bm25, vector, request(3), null);

        assertThat(result).extracting(RetrievedChunk::chunkId).containsExactly(11L, 10L, 12L);
        assertThat(result.get(0).channels())
                .containsExactlyInAnyOrder(RetrievalChannel.BM25, RetrievalChannel.VECTOR);
        assertThat(result.get(0).bm25Score()).isEqualTo(5.0D);
        assertThat(result.get(0).vectorScore()).isEqualTo(0.90D);
        assertThat(result.get(0).rrfScore())
                .isCloseTo(1.0D / 62 + 1.0D / 61, org.assertj.core.data.Offset.offset(0.0000001D));
    }

    @Test
    void shouldMergeCanonicalSameVersionOnlyOncePerChannelAcrossDifferentPhysicalIds() {
        KnowledgeIndexService service = service();
        var source =
                Map.<String, Object>of(
                        "documentId",
                        1036L,
                        "chunkId",
                        1054L,
                        "documentVersion",
                        1,
                        "version",
                        1,
                        "content",
                        "public test");
        var bm25 =
                List.of(
                        new RetrievalHit("1036:1:1", 8, source),
                        new RetrievalHit("legacy-alias-for-same-chunk", 7, source));
        var vector = List.of(new RetrievalHit("1054", .9, source));
        var result = service.fuse(bm25, vector, request(5), null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).chunkId()).isEqualTo(1054);
        assertThat(result.get(0).channels())
                .containsExactlyInAnyOrder(RetrievalChannel.BM25, RetrievalChannel.VECTOR);
        assertThat(result.get(0).rrfScore())
                .isCloseTo(2D / 61, org.assertj.core.data.Offset.offset(.0000001D));
    }

    @Test
    void shouldNotMergeConflictingOrUnknownVersions() {
        KnowledgeIndexService service = service();
        var first =
                new RetrievalHit(
                        "same-physical-id",
                        8,
                        Map.of("documentId", 1L, "chunkId", 5L, "documentVersion", 1));
        var second =
                new RetrievalHit(
                        "same-physical-id",
                        .9,
                        Map.of("documentId", 1L, "chunkId", 5L, "documentVersion", 2));
        var unknown = new RetrievalHit("legacy", .8, Map.of("documentId", 1L, "chunkId", 5L));
        var mismatched =
                new RetrievalHit(
                        "broken-metadata",
                        .7,
                        Map.of(
                                "documentId",
                                1L,
                                "chunkId",
                                5L,
                                "documentVersion",
                                1,
                                "version",
                                2));
        var result =
                service.fuse(
                        List.of(first), List.of(second, unknown, mismatched), request(5), null);
        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(row -> assertThat(row.channels()).hasSize(1));
    }

    @Test
    void shouldExcludeMissingAndInvalidIdentitiesRatherThanMergingThem() {
        KnowledgeIndexService service = service();
        var invalid = new java.util.ArrayList<RetrievalHit>();
        invalid.add(new RetrievalHit("same", 1, Map.of()));
        invalid.add(new RetrievalHit("same", 1, Map.of("documentId", 1L)));
        invalid.add(
                new RetrievalHit("same", 1, Map.of("documentId", 1L, "chunkId", 5L, "version", 0)));
        for (Object value :
                List.of(
                        -1L,
                        0L,
                        1.5D,
                        "5",
                        true,
                        new java.math.BigInteger("9223372036854775808"))) {
            invalid.add(new RetrievalHit("same", 1, Map.of("documentId", 1L, "chunkId", value)));
            invalid.add(new RetrievalHit("same", 1, Map.of("documentId", value, "chunkId", 5L)));
        }
        assertThat(service.fuse(invalid, invalid, request(20), null)).isEmpty();
    }

    private KnowledgeIndexService service() {
        VectorProperties properties = new VectorProperties();
        properties.setRrfRankConstant(60);
        return new KnowledgeIndexService(
                properties,
                mock(EmbeddingClient.class),
                mock(ElasticsearchVectorStore.class),
                mock(QdrantVectorStore.class),
                mock(KnowledgeRepository.class),
                new ObjectMapper(),
                new ApproxTokenCounter(),
                new QueryNormalizer(),
                new SimpleMeterRegistry());
    }

    private RetrievalRequest request(int resultSize) {
        return new RetrievalRequest(
                "Redis error 429",
                3L,
                9L,
                null,
                null,
                Set.of(3L, 4L),
                false,
                7L,
                false,
                resultSize);
    }

    private RetrievalHit hit(String id, long chunkId, double score) {
        return new RetrievalHit(
                id,
                score,
                Map.of(
                        "chunkId",
                        chunkId,
                        "documentId",
                        1L,
                        "chunkIndex",
                        (int) chunkId,
                        "documentName",
                        "Redis SOP",
                        "headingPath",
                        List.of("告警", "排查"),
                        "content",
                        "缓存命中率下降",
                        "pageStart",
                        1,
                        "pageEnd",
                        1));
    }
}
