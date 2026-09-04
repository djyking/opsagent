package com.opsagent.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理 Qdrant Collection、向量幂等写入、权限过滤和相似度检索。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class QdrantVectorStore {
    private static final TypeReference<Map<String, Object>> PAYLOAD_MAP_TYPE = new TypeReference<>() {};
    private final VectorProperties properties;
    private final ObjectMapper mapper;
    private final RestClient client;
    private volatile boolean collectionReady;

    QdrantVectorStore(VectorProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getQdrantUrl());
        if (properties.getQdrantApiKey() != null
                && !properties.getQdrantApiKey().isBlank()) {
            builder.defaultHeader("api-key", properties.getQdrantApiKey());
        }
        this.client = builder.build();
    }

    synchronized void ensureCollection() {
        if (collectionReady) {
            return;
        }
        if (collectionExists(properties.getQdrantCollection())) {
            validateDimensions(properties.getQdrantCollection());
        } else {
            createCollection(properties.getQdrantCollection());
        }
        ensureAlias(properties.getQdrantAlias(), properties.getQdrantCollection());
        collectionReady = true;
    }

    BulkUpsertResult bulkUpsert(List<IndexPoint> points) {
        ensureCollection();
        return bulkUpsert(properties.getQdrantAlias(), points);
    }

    BulkUpsertResult bulkUpsert(String targetCollection, List<IndexPoint> points) {
        if (points.isEmpty()) {
            return new BulkUpsertResult(List.of(), Map.of());
        }
        List<Map<String, Object>> bodyPoints = points.stream()
                .map(point -> Map.<String, Object>of(
                        "id", point.chunkId(),
                        "vector", point.vector(),
                        "payload", point.payload()))
                .toList();
        client.put()
                .uri("/collections/" + targetCollection + "/points?wait=true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", bodyPoints))
                .retrieve()
                .toBodilessEntity();
        return new BulkUpsertResult(
                points.stream().map(IndexPoint::chunkId).toList(), Map.of());
    }

    List<RetrievalHit> vectorSearch(
            List<Double> queryVector,
            RetrievalRequest request,
            int topK) {
        ensureCollection();
        JsonNode response = client.post()
                .uri("/collections/" + properties.getQdrantAlias() + "/points/query")
                .contentType(MediaType.APPLICATION_JSON)
                .body(searchBody(queryVector, request, topK))
                .retrieve()
                .body(JsonNode.class);
        List<RetrievalHit> rows = new ArrayList<>();
        response.path("result").path("points").forEach(point -> {
            Map<String, Object> payload = mapper.convertValue(
                    point.path("payload"), PAYLOAD_MAP_TYPE);
            rows.add(new RetrievalHit(
                    point.path("id").asText(), point.path("score").asDouble(), payload));
        });
        return rows;
    }

    Map<String, Object> searchBody(
            List<Double> queryVector,
            RetrievalRequest request,
            int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", queryVector);
        body.put("filter", mandatoryFilters(request));
        body.put("limit", topK);
        body.put("score_threshold", properties.getMinimumScore());
        body.put("with_payload", true);
        body.put("with_vector", false);
        body.put("params", Map.of(
                "hnsw_ef", Math.max(topK, properties.getVectorCandidates()),
                "exact", false));
        return body;
    }

    Map<String, Object> mandatoryFilters(RetrievalRequest request) {
        List<Map<String, Object>> must = new ArrayList<>();
        if (!request.administratorPreview()) {
            must.add(match("reviewStatus", "PUBLISHED"));
        }
        if (request.documentId() != null) {
            must.add(match("documentId", request.documentId()));
        }
        if (request.knowledgeBaseId() != null) {
            must.add(match("knowledgeBaseId", request.knowledgeBaseId()));
        }
        Collection<Long> allowed = request.allowedKnowledgeBaseIds();
        if (allowed != null && !allowed.isEmpty()) {
            must.add(Map.of(
                    "key", "knowledgeBaseId",
                    "match", Map.of("any", allowed)));
        }
        if (!request.administrator()) {
            must.add(Map.of("should", List.of(
                    match("visibility", "PUBLIC"),
                    match("createBy", request.userId()))));
        }
        return Map.of("must", must);
    }

    long deleteDocument(long documentId) {
        return deleteByFilter(Map.of("must", List.of(match("documentId", documentId))));
    }

    long deleteOlderVersions(long documentId, int currentVersion) {
        Map<String, Object> filter = Map.of(
                "must", List.of(match("documentId", documentId)),
                "must_not", List.of(match("documentVersion", currentVersion)));
        return deleteByFilter(filter);
    }

    String createVersionedCollection() {
        String collection = properties.getQdrantCollection() + "_" + System.currentTimeMillis();
        createCollection(collection);
        return collection;
    }

    void switchAlias(String targetCollection) {
        String current = physicalCollection();
        List<Map<String, Object>> actions = new ArrayList<>();
        if (!current.isBlank() && !current.equals(targetCollection)) {
            actions.add(Map.of("delete_alias", Map.of(
                    "alias_name", properties.getQdrantAlias())));
        }
        if (!current.equals(targetCollection)) {
            actions.add(Map.of("create_alias", Map.of(
                    "collection_name", targetCollection,
                    "alias_name", properties.getQdrantAlias())));
        }
        if (!actions.isEmpty()) {
            updateAliases(actions);
        }
        collectionReady = true;
    }

    String physicalCollection() {
        JsonNode aliases = client.get().uri("/aliases").retrieve().body(JsonNode.class);
        for (JsonNode alias : aliases.path("result").path("aliases")) {
            if (properties.getQdrantAlias().equals(alias.path("alias_name").asText())) {
                return alias.path("collection_name").asText();
            }
        }
        return "";
    }

    long pointCount() {
        ensureCollection();
        return pointCount(properties.getQdrantAlias());
    }

    long pointCount(String collection) {
        JsonNode response = client.post()
                .uri("/collections/" + collection + "/points/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("exact", true))
                .retrieve()
                .body(JsonNode.class);
        return response.path("result").path("count").asLong();
    }

    private long deleteByFilter(Map<String, Object> filter) {
        try {
            JsonNode countResponse = client.post()
                    .uri("/collections/" + properties.getQdrantAlias() + "/points/count")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("filter", filter, "exact", true))
                    .retrieve()
                    .body(JsonNode.class);
            long count = countResponse.path("result").path("count").asLong();
            client.post()
                    .uri("/collections/" + properties.getQdrantAlias()
                            + "/points/delete?wait=true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("filter", filter))
                    .retrieve()
                    .toBodilessEntity();
            return count;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return 0L;
            }
            throw new IllegalStateException("Qdrant 向量删除失败", exception);
        }
    }

    private void createCollection(String collection) {
        client.put()
                .uri("/collections/" + collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "vectors", Map.of(
                                "size", properties.getDimensions(),
                                "distance", "Cosine"),
                        "on_disk_payload", true))
                .retrieve()
                .toBodilessEntity();
    }

    private boolean collectionExists(String collection) {
        try {
            client.get().uri("/collections/" + collection).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return false;
            }
            throw new IllegalStateException("Qdrant Collection 检查失败", exception);
        }
    }

    private void validateDimensions(String collection) {
        JsonNode response = client.get()
                .uri("/collections/" + collection)
                .retrieve()
                .body(JsonNode.class);
        int actual = response.path("result")
                .path("config")
                .path("params")
                .path("vectors")
                .path("size")
                .asInt(-1);
        if (actual != properties.getDimensions()) {
            throw new IllegalStateException(
                    "Qdrant 向量维度不匹配，配置=" + properties.getDimensions()
                            + "，Collection=" + actual);
        }
    }

    private void ensureAlias(String alias, String collection) {
        String current = physicalCollection();
        if (current.isBlank()) {
            updateAliases(List.of(Map.of("create_alias", Map.of(
                    "collection_name", collection,
                    "alias_name", alias))));
        }
    }

    private void updateAliases(List<Map<String, Object>> actions) {
        client.post()
                .uri("/collections/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("actions", actions))
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> match(String key, Object value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    /**
     * 描述一次 Qdrant Upsert 的稳定点标识、向量和权限 Payload。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record IndexPoint(long chunkId, List<Double> vector, Map<String, Object> payload) {}

    /**
     * 汇总一次 Qdrant 批量写入成功点和失败原因。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record BulkUpsertResult(List<Long> succeededChunkIds, Map<Long, String> failures) {}
}
