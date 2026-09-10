package com.opsagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Verifies bounded parallel retrieval and request-local query reuse without weakening scope
 * filters.
 *
 * @author heyu
 * @since 2026/9/3
 */
class QueryRetrievalConcurrencyTest {
    @Test
    void reusesNormalizedVectorOnlyWithinOneRequestAndRejectsOtherQueries() {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.embedBatch(anyList())).thenReturn(batch());
        QueryEmbedding first = new QueryEmbedding(embedding, " Ｒedis  429 ");
        assertThat(first.get("Redis 429")).isSameAs(first.get(" Redis 429 "));
        assertThatThrownBy(() -> first.get("different"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(embedding).embedBatch(List.of("Redis 429"));
        new QueryEmbedding(embedding, "Redis 429").get("Redis 429");
        verify(embedding, times(2)).embedBatch(List.of("Redis 429"));
    }

    @Test
    void aFailedEmbeddingIsNotSilentlyBilledAgainInTheSameRequest() {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.embedBatch(anyList())).thenThrow(new IllegalStateException("unavailable"));
        QueryEmbedding query = new QueryEmbedding(embedding, "Redis");
        assertThatThrownBy(() -> query.get("Redis")).hasMessage("unavailable");
        assertThatThrownBy(() -> query.get("Redis")).hasMessage("unavailable");
        verify(embedding).embedBatch(List.of("Redis"));
    }

    @Test
    void bm25AndEmbeddingActuallyOverlapAndKeepTheSamePermissionScope() throws Exception {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.configured()).thenReturn(true);
        ElasticsearchVectorStore keywords = mock(ElasticsearchVectorStore.class);
        QdrantVectorStore vectors = mock(QdrantVectorStore.class);
        CountDownLatch bothStarted = new CountDownLatch(2);
        when(keywords.bm25Search(eq("Redis"), any(), anyInt()))
                .thenAnswer(
                        call -> {
                            bothStarted.countDown();
                            assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
                            return List.of(hit());
                        });
        when(embedding.embedBatch(List.of("Redis")))
                .thenAnswer(
                        call -> {
                            bothStarted.countDown();
                            assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
                            return batch();
                        });
        when(vectors.vectorSearch(anyList(), any(), anyInt())).thenReturn(List.of(hit()));
        KnowledgeIndexService index = index(embedding, keywords, vectors);
        var request = request();
        try {
            var result = index.search(request);
            assertThat(result.retrievalMode()).isEqualTo("HYBRID_RRF");
            assertThat(result.candidates()).extracting(RetrievedChunk::chunkId).containsExactly(5L);
            verify(keywords).bm25Search(eq("Redis"), eq(request), anyInt());
            verify(vectors).vectorSearch(eq(List.of(0.1, 0.2)), eq(request), anyInt());
        } finally {
            index.closeQueryWorkers();
        }
    }

    @Test
    void vectorFailureStillReturnsScopedKeywordEvidence() {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.configured()).thenReturn(true);
        when(embedding.embedBatch(anyList())).thenThrow(new IllegalStateException("unavailable"));
        ElasticsearchVectorStore keywords = mock(ElasticsearchVectorStore.class);
        when(keywords.bm25Search(eq("Redis"), any(), anyInt())).thenReturn(List.of(hit()));
        KnowledgeIndexService index = index(embedding, keywords, mock(QdrantVectorStore.class));
        try {
            var result = index.search(request());
            assertThat(result.retrievalMode()).isEqualTo("BM25");
            assertThat(result.degradedReason()).isEqualTo("EMBEDDING_OR_VECTOR_UNAVAILABLE");
            assertThat(result.candidates()).extracting(RetrievedChunk::chunkId).containsExactly(5L);
        } finally {
            index.closeQueryWorkers();
        }
    }

    private KnowledgeIndexService index(
            EmbeddingClient embedding,
            ElasticsearchVectorStore keywords,
            QdrantVectorStore vectors) {
        VectorProperties properties = new VectorProperties();
        properties.setEnabled(true);
        return new KnowledgeIndexService(
                properties,
                embedding,
                keywords,
                vectors,
                mock(KnowledgeRepository.class),
                new ObjectMapper(),
                new ApproxTokenCounter(),
                new QueryNormalizer(),
                new SimpleMeterRegistry());
    }

    private RetrievalRequest request() {
        return new RetrievalRequest(
                "Redis", 3L, 9L, null, null, Set.of(3L), false, -101L, false, 5);
    }

    private EmbeddingBatchResult batch() {
        return new EmbeddingBatchResult(List.of(List.of(0.1, 0.2)), "test", 2, 7);
    }

    private RetrievalHit hit() {
        return new RetrievalHit(
                "5",
                1,
                Map.of(
                        "chunkId",
                        5L,
                        "documentId",
                        9L,
                        "documentVersion",
                        1,
                        "chunkIndex",
                        0,
                        "content",
                        "Redis evidence"));
    }
}
