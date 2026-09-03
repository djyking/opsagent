package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理 Elasticsearch dense_vector 索引、幂等写入和带权限过滤的 KNN 检索。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Component
public class ElasticsearchVectorStore {
    private final VectorProperties properties;
    private final ObjectMapper mapper;
    private final RestClient client;
    private volatile boolean indexReady;

    ElasticsearchVectorStore(VectorProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = RestClient.builder().baseUrl(properties.getElasticsearchUrl()).build();
    }

    synchronized void ensureIndex() {
        if (indexReady) {
            return;
        }
        try {
            client.get().uri("/" + properties.getIndexName()).retrieve().toBodilessEntity();
            indexReady = true;
            return;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404) {
                throw new IllegalStateException("Elasticsearch 索引检查失败", exception);
            }
        }
        Map<String, Object> denseVector = Map.of(
                "type", "dense_vector",
                "dims", properties.getDimensions(),
                "index", true,
                "similarity", "cosine");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chunkId", Map.of("type", "long"));
        fields.put("documentId", Map.of("type", "long"));
        fields.put("chunkIndex", Map.of("type", "integer"));
        fields.put("content", Map.of("type", "text", "index", false));
        fields.put("documentName", Map.of("type", "keyword"));
        fields.put("page", Map.of("type", "integer"));
        fields.put("version", Map.of("type", "integer"));
        fields.put("updateTime", Map.of("type", "date", "ignore_malformed", true));
        fields.put("visibility", Map.of("type", "keyword"));
        fields.put("createBy", Map.of("type", "long"));
        fields.put("embeddingModel", Map.of("type", "keyword"));
        fields.put("embedding", denseVector);
        try {
            client.put()
                    .uri("/" + properties.getIndexName())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("mappings", Map.of("properties", fields)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 400) {
                throw new IllegalStateException("Elasticsearch 索引创建失败", exception);
            }
        }
        indexReady = true;
    }

    void index(String id, Map<String, Object> document) {
        ensureIndex();
        client.put()
                .uri("/" + properties.getIndexName() + "/_doc/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(document)
                .retrieve()
                .toBodilessEntity();
    }

    long deleteDocument(long documentId) {
        try {
            JsonNode response = client.post()
                    .uri("/" + properties.getIndexName()
                            + "/_delete_by_query?conflicts=proceed&refresh=true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", Map.of("term", Map.of("documentId", documentId))))
                    .retrieve()
                    .body(JsonNode.class);
            return response.path("deleted").asLong();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return 0L;
            }
            throw new IllegalStateException("Elasticsearch 文档删除失败", exception);
        }
    }

    List<Map<String, Object>> search(
            List<Double> queryVector,
            long userId,
            boolean administrator,
            int topK,
            Long documentId) {
        ensureIndex();
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", "embedding");
        knn.put("query_vector", queryVector);
        knn.put("k", topK);
        knn.put("num_candidates", Math.max(50, topK * 10));
        List<Map<String, Object>> filters = new ArrayList<>();
        if (documentId != null) {
            filters.add(Map.of("term", Map.of("documentId", documentId)));
        }
        if (!administrator) {
            List<Map<String, Object>> access = List.of(
                    Map.of("term", Map.of("visibility", "PUBLIC")),
                    Map.of("term", Map.of("createBy", userId)));
            filters.add(Map.of(
                    "bool", Map.of("should", access, "minimum_should_match", 1)));
        }
        if (filters.size() == 1) {
            knn.put("filter", filters.get(0));
        } else if (!filters.isEmpty()) {
            knn.put("filter", Map.of("bool", Map.of("must", filters)));
        }
        JsonNode response = client.post()
                .uri("/" + properties.getIndexName() + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("size", topK, "knn", knn))
                .retrieve()
                .body(JsonNode.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        response.path("hits").path("hits").forEach(hit -> {
            double score = hit.path("_score").asDouble();
            if (score < properties.getMinimumScore()) {
                return;
            }
            Map<String, Object> source = mapper.convertValue(hit.path("_source"), Map.class);
            source.remove("embedding");
            source.put("score", score);
            rows.add(source);
        });
        return rows;
    }
}
