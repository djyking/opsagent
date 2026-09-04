package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Qdrant Collection 检查、权限过滤查询和 Payload 解析。
 *
 * @author heyu
 * @since 2026/9/3
 */
class QdrantVectorStoreTest {

    @Test
    void shouldQueryQdrantWithPermissionFilterAndParsePayload() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(json("{\"result\":{}}"));
        server.enqueue(json("""
                {"result":{"config":{"params":{"vectors":{"size":3}}}}}
                """));
        server.enqueue(json("""
                {"result":{"aliases":[{"alias_name":"vector_read",
                  "collection_name":"vector_v1"}]}}
                """));
        server.enqueue(json("""
                {"result":{"points":[{"id":31,"score":0.91,"payload":{
                  "chunkId":31,"documentId":7,"chunkIndex":0,
                  "documentName":"Redis SOP","content":"先检查主从状态"}}]}}
                """));
        server.start();
        try {
            VectorProperties properties = new VectorProperties();
            properties.setQdrantUrl(server.url("/").toString());
            properties.setQdrantCollection("vector_v1");
            properties.setQdrantAlias("vector_read");
            properties.setDimensions(3);
            QdrantVectorStore store = new QdrantVectorStore(
                    properties, new ObjectMapper());
            RetrievalRequest request = new RetrievalRequest(
                    "Redis", 2L, 7L, null, null, Set.of(2L, 3L),
                    false, 9L, false, 5);

            List<RetrievalHit> hits = store.vectorSearch(
                    List.of(0.1D, 0.2D, 0.3D), request, 5);

            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).score()).isEqualTo(0.91D);
            assertThat(hits.get(0).source()).containsEntry("chunkId", 31);
            assertThat(server.takeRequest().getPath()).isEqualTo("/collections/vector_v1");
            assertThat(server.takeRequest().getPath()).isEqualTo("/collections/vector_v1");
            assertThat(server.takeRequest().getPath()).isEqualTo("/aliases");
            RecordedRequest query = server.takeRequest();
            assertThat(query.getPath()).isEqualTo("/collections/vector_read/points/query");
            assertThat(query.getBody().readUtf8())
                    .contains("score_threshold", "reviewStatus", "PUBLISHED")
                    .contains("knowledgeBaseId", "visibility", "PUBLIC", "createBy");
        } finally {
            server.shutdown();
        }
    }

    private MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
