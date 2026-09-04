package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 OpenAI Embedding 批量响应、维度检查、ES Mapping 和 Bulk 部分失败解析。
 *
 * @author heyu
 * @since 2026/9/3
 */
class EmbeddingAndIndexTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldParseBatchEmbeddingAndTokenUsage() throws Exception {
        MockWebServer server = embeddingServer("""
                {"data":[
                  {"index":1,"embedding":[0.4,0.5,0.6]},
                  {"index":0,"embedding":[0.1,0.2,0.3]}
                ],"usage":{"total_tokens":17}}
                """);
        try {
            VectorProperties properties = properties(server, 3);
            EmbeddingBatchResult result = new OpenAiEmbeddingClient(
                    properties, new SimpleMeterRegistry())
                    .embedBatch(List.of("Redis", "RabbitMQ"));

            assertThat(result.model()).isEqualTo("text-embedding-3-small");
            assertThat(result.dimensions()).isEqualTo(3);
            assertThat(result.tokenUsage()).isEqualTo(17);
            assertThat(result.vectors().get(0)).containsExactly(0.1D, 0.2D, 0.3D);
            RecordedRequest request = server.takeRequest();
            assertThat(request.getBody().readUtf8())
                    .contains("\"dimensions\":3", "Redis", "RabbitMQ");
        } finally {
            server.shutdown();
        }
    }

    @Test
    void shouldRejectEmbeddingDimensionMismatch() throws Exception {
        MockWebServer server = embeddingServer("""
                {"data":[{"index":0,"embedding":[0.1,0.2]}],"usage":{"total_tokens":2}}
                """);
        try {
            OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
                    properties(server, 3), new SimpleMeterRegistry());

            assertThatThrownBy(() -> client.embedBatch(List.of("Redis")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("维度");
        } finally {
            server.shutdown();
        }
    }

    @Test
    void shouldBuildSmartCnDenseVectorMapping() {
        VectorProperties properties = new VectorProperties();
        properties.setDimensions(768);
        ElasticsearchVectorStore store = new ElasticsearchVectorStore(properties, mapper);

        JsonNode definition = mapper.valueToTree(store.indexDefinition("smartcn"));

        JsonNode fields = definition.path("mappings").path("properties");
        assertThat(fields.path("content").path("analyzer").asText()).isEqualTo("smartcn");
        assertThat(fields.path("content").path("type").asText()).isEqualTo("text");
        assertThat(fields.path("embedding").path("type").asText()).isEqualTo("dense_vector");
        assertThat(fields.path("embedding").path("dims").asInt()).isEqualTo(768);
        assertThat(fields.path("documentName").path("fields").path("raw").path("type").asText())
                .isEqualTo("keyword");
    }

    @Test
    void shouldKeepBulkPartialFailureVisible() throws Exception {
        ElasticsearchVectorStore store = new ElasticsearchVectorStore(new VectorProperties(), mapper);
        List<ElasticsearchVectorStore.IndexDocument> documents = List.of(
                new ElasticsearchVectorStore.IndexDocument("1:1:0", 10L, Map.of("chunkId", 10L)),
                new ElasticsearchVectorStore.IndexDocument("1:1:1", 11L, Map.of("chunkId", 11L)));
        JsonNode response = mapper.readTree("""
                {"items":[
                  {"index":{"status":201}},
                  {"index":{"status":400,"error":{"type":"mapper_parsing_exception",
                    "reason":"invalid field"}}}
                ]}
                """);

        ElasticsearchVectorStore.BulkIndexResult result = store.parseBulkResult(response, documents);

        assertThat(result.succeededChunkIds()).containsExactly(10L);
        assertThat(result.failures()).containsKey(11L);
        assertThat(result.failures().get(11L)).contains("mapper_parsing_exception", "invalid field");
    }

    private MockWebServer embeddingServer(String response) throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(response));
        server.start();
        return server;
    }

    private VectorProperties properties(MockWebServer server, int dimensions) {
        VectorProperties properties = new VectorProperties();
        properties.setEnabled(true);
        properties.setEmbeddingApiKey("test-key");
        properties.setDimensions(dimensions);
        properties.setEmbeddingBaseUrl(server.url("/").toString());
        return properties;
    }
}
