package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 验证 ES composite 与 Qdrant scroll 翻页，不将不完整响应判为正常。
 *
 * @author heyu
 * @since 2026/9/3
 */
class KnowledgeIndexIdentityScanTest {
    @Test
    void shouldReadEveryElasticsearchCompositePage() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("{\"aggregations\":{\"documents\":{\"buckets\":[{\"key\":{\"documentId\":1}}],"
                    + "\"after_key\":{\"documentId\":1}}}}"));
            server.enqueue(json("{\"aggregations\":{\"documents\":{\"buckets\":[{\"key\":{\"documentId\":2}}]}}}"));
            server.start();
            var properties = new VectorProperties();
            properties.setElasticsearchUrl(server.url("/").toString());
            var store = new ElasticsearchVectorStore(properties, new ObjectMapper());
            assertThat(store.indexedDocumentIds("physical-v2")).containsExactly(1L, 2L);
            assertThat(server.takeRequest().getPath()).isEqualTo("/physical-v2/_search");
            assertThat(server.takeRequest().getBody().readUtf8()).contains("\"after\":{\"documentId\":1}");
        }
    }

    @Test
    void shouldReadEveryQdrantScrollPageWithoutVectorsOrPayloads() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("{\"result\":{\"points\":[{\"id\":10}],\"next_page_offset\":10}}"));
            server.enqueue(json("{\"result\":{\"points\":[{\"id\":20}],\"next_page_offset\":null}}"));
            server.start();
            var properties = new VectorProperties();
            properties.setQdrantUrl(server.url("/").toString());
            var store = new QdrantVectorStore(properties, new ObjectMapper());
            assertThat(store.pointIds("physical-v2")).containsExactly("10", "20");
            assertThat(server.takeRequest().getBody().readUtf8())
                    .contains("\"with_payload\":false", "\"with_vector\":false");
            assertThat(server.takeRequest().getBody().readUtf8()).contains("\"offset\":10");
        }
    }

    @Test
    void shouldRejectIncompleteIdentityResponses() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("{}"));
            server.enqueue(json("{\"result\":{}}"));
            server.start();
            var properties = new VectorProperties();
            properties.setElasticsearchUrl(server.url("/").toString());
            properties.setQdrantUrl(server.url("/").toString());
            var elasticsearch = new ElasticsearchVectorStore(properties, new ObjectMapper());
            assertThatThrownBy(() -> elasticsearch.indexedDocumentIds("v1"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("完整");
            assertThatThrownBy(() -> new QdrantVectorStore(properties, new ObjectMapper()).pointIds("v1"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("完整");
        }
    }

    private MockResponse json(String body) {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody(body);
    }
}
