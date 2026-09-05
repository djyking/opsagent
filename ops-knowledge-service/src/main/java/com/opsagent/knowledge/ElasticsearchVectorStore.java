package com.opsagent.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理 Elasticsearch 中文文本索引、幂等写入和带权限过滤的 BM25 检索。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Component
public class ElasticsearchVectorStore {
    private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchVectorStore.class);
    private static final TypeReference<Map<String, Object>> SOURCE_MAP_TYPE = new TypeReference<>() {};
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
        if (!indexExists(properties.getIndexName())) {
            createIndex(properties.getIndexName());
        }
        ensureAlias(properties.getReadAlias(), properties.getIndexName(), false);
        ensureAlias(properties.getWriteAlias(), properties.getIndexName(), true);
        indexReady = true;
    }

    void index(String id, Map<String, Object> document) {
        BulkIndexResult result = bulkIndex(List.of(new IndexDocument(id, number(document, "chunkId"), document)));
        if (!result.failures().isEmpty()) {
            throw new IllegalStateException("Elasticsearch 单条索引失败：" + result.failures().values().iterator().next());
        }
    }

    BulkIndexResult bulkIndex(List<IndexDocument> documents) {
        ensureIndex();
        return bulkIndex(properties.getWriteAlias(), documents);
    }

    BulkIndexResult bulkIndex(String targetIndex, List<IndexDocument> documents) {
        if (documents.isEmpty()) {
            return new BulkIndexResult(List.of(), Map.of());
        }
        StringBuilder body = new StringBuilder();
        try {
            for (IndexDocument document : documents) {
                body.append(mapper.writeValueAsString(Map.of(
                        "index", Map.of("_index", targetIndex, "_id", document.id()))))
                        .append('\n');
                body.append(mapper.writeValueAsString(document.source())).append('\n');
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Elasticsearch Bulk 请求无法序列化", exception);
        }
        JsonNode response = client.post()
                .uri("/_bulk?refresh=wait_for")
                .contentType(new MediaType("application", "x-ndjson", StandardCharsets.UTF_8))
                // StringHttpMessageConverter 对非 application/json 字符串默认使用 ISO-8859-1。
                .body(body.toString().getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .body(JsonNode.class);
        return parseBulkResult(response, documents);
    }

    String createVersionedIndex() {
        String indexName = properties.getIndexName() + "_" + System.currentTimeMillis();
        createIndex(indexName);
        return indexName;
    }

    void switchAliases(String targetIndex) {
        List<Map<String, Object>> actions = List.of(
                Map.of("remove", Map.of(
                        "index", "*", "alias", properties.getReadAlias(), "must_exist", false)),
                Map.of("remove", Map.of(
                        "index", "*", "alias", properties.getWriteAlias(), "must_exist", false)),
                Map.of("add", Map.of("index", targetIndex, "alias", properties.getReadAlias())),
                Map.of("add", Map.of(
                        "index", targetIndex,
                        "alias", properties.getWriteAlias(),
                        "is_write_index", true)));
        client.post()
                .uri("/_aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("actions", actions))
                .retrieve()
                .toBodilessEntity();
        indexReady = true;
    }

    String physicalIndex() {
        ensureIndex();
        JsonNode response = client.get()
                .uri("/_alias/" + properties.getReadAlias())
                .retrieve()
                .body(JsonNode.class);
        return response.fieldNames().hasNext() ? response.fieldNames().next() : "";
    }

    long indexedDocumentCount() {
        ensureIndex();
        JsonNode response = client.post()
                .uri("/" + properties.getReadAlias() + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "size", 0,
                        "query", Map.of("term", Map.of("reviewStatus", "PUBLISHED")),
                        "aggs", Map.of("documents", Map.of(
                                "cardinality", Map.of("field", "documentId")))))
                .retrieve()
                .body(JsonNode.class);
        return response.path("aggregations").path("documents").path("value").asLong();
    }

    long documentCount(String indexName) {
        JsonNode response = client.post()
                .uri("/" + indexName + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "size", 0,
                        "aggs", Map.of("documents", Map.of(
                                "cardinality", Map.of("field", "documentId")))))
                .retrieve()
                .body(JsonNode.class);
        return response.path("aggregations").path("documents").path("value").asLong();
    }

    long deleteDocument(long documentId) {
        try {
            JsonNode response = client.post()
                    .uri("/" + properties.getReadAlias()
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

    List<RetrievalHit> bm25Search(String query, RetrievalRequest request, int topK) {
        ensureIndex();
        List<Map<String, Object>> should = new ArrayList<>();
        should.add(Map.of("multi_match", Map.of(
                "query", query,
                "fields", List.of("documentName^4", "headingPath^2.5", "content"),
                "type", "best_fields")));
        for (String identifier : exactIdentifiers(query)) {
            should.add(Map.of("match_phrase", Map.of(
                    "documentName", Map.of("query", identifier, "boost", 8))));
            should.add(Map.of("match_phrase", Map.of(
                    "headingPath", Map.of("query", identifier, "boost", 6))));
            should.add(Map.of("match_phrase", Map.of(
                    "content", Map.of("query", identifier, "boost", 5))));
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("should", should);
        bool.put("minimum_should_match", 1);
        bool.put("filter", mandatoryFilters(request));
        JsonNode response = client.post()
                .uri("/" + properties.getReadAlias() + "/_search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "size", topK,
                        "track_total_hits", false,
                        "query", Map.of("bool", bool)))
                .retrieve()
                .body(JsonNode.class);
        return parseHits(response);
    }

    long deleteOlderVersions(long documentId, int currentVersion) {
        Map<String, Object> bool = Map.of(
                "must", List.of(Map.of("term", Map.of("documentId", documentId))),
                "must_not", List.of(Map.of("term", Map.of("documentVersion", currentVersion))));
        JsonNode response = client.post()
                .uri("/" + properties.getReadAlias()
                        + "/_delete_by_query?conflicts=proceed&refresh=true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", Map.of("bool", bool)))
                .retrieve()
                .body(JsonNode.class);
        return response.path("deleted").asLong();
    }

    Map<String, Object> indexDefinition(String analyzer) {
        Map<String, Object> textField = new LinkedHashMap<>();
        textField.put("type", "text");
        textField.put("analyzer", analyzer);
        textField.put("fields", Map.of("raw", Map.of("type", "keyword", "ignore_above", 512)));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chunkId", Map.of("type", "long"));
        fields.put("documentId", Map.of("type", "long"));
        fields.put("documentVersion", Map.of("type", "integer"));
        fields.put("version", Map.of("type", "integer"));
        fields.put("knowledgeBaseId", Map.of("type", "long"));
        fields.put("chunkIndex", Map.of("type", "integer"));
        fields.put("documentName", textField);
        fields.put("headingPath", textField);
        fields.put("content", Map.of("type", "text", "analyzer", analyzer));
        fields.put("page", Map.of("type", "integer"));
        fields.put("pageStart", Map.of("type", "integer"));
        fields.put("pageEnd", Map.of("type", "integer"));
        fields.put("visibility", Map.of("type", "keyword"));
        fields.put("reviewStatus", Map.of("type", "keyword"));
        fields.put("createBy", Map.of("type", "long"));
        fields.put("sourceFormat", Map.of("type", "keyword"));
        fields.put("blockTypes", Map.of("type", "keyword"));
        fields.put("containsCode", Map.of("type", "boolean"));
        fields.put("containsTable", Map.of("type", "boolean"));
        fields.put("chunkStrategyVersion", Map.of("type", "keyword"));
        fields.put("embeddingModel", Map.of("type", "keyword"));
        fields.put("embeddingDimensions", Map.of("type", "integer"));
        fields.put("contentHash", Map.of("type", "keyword"));
        fields.put("createTime", Map.of("type", "date", "ignore_malformed", true));
        fields.put("updateTime", Map.of("type", "date", "ignore_malformed", true));
        return Map.of(
                "settings", Map.of("number_of_replicas", 0),
                "mappings", Map.of("dynamic", "strict", "properties", fields));
    }

    BulkIndexResult parseBulkResult(JsonNode response, List<IndexDocument> documents) {
        List<Long> succeeded = new ArrayList<>();
        Map<Long, String> failures = new LinkedHashMap<>();
        JsonNode items = response.path("items");
        for (int index = 0; index < documents.size(); index++) {
            IndexDocument document = documents.get(index);
            JsonNode item = items.path(index).path("index");
            int status = item.path("status").asInt(500);
            if (status >= 200 && status < 300) {
                succeeded.add(document.chunkId());
            } else {
                String type = item.path("error").path("type").asText("unknown");
                String reason = item.path("error").path("reason").asText("bulk item failed");
                failures.put(document.chunkId(), type + ": " + reason);
            }
        }
        return new BulkIndexResult(List.copyOf(succeeded), Map.copyOf(failures));
    }

    private List<RetrievalHit> parseHits(JsonNode response) {
        List<RetrievalHit> rows = new ArrayList<>();
        response.path("hits").path("hits").forEach(hit -> {
            Map<String, Object> source = mapper.convertValue(hit.path("_source"), SOURCE_MAP_TYPE);
            source.remove("embedding");
            rows.add(new RetrievalHit(
                    hit.path("_id").asText(), hit.path("_score").asDouble(), source));
        });
        return rows;
    }

    List<Map<String, Object>> mandatoryFilters(RetrievalRequest request) {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (!request.administratorPreview()) {
            filters.add(Map.of("term", Map.of("reviewStatus", "PUBLISHED")));
        }
        if (request.documentId() != null) {
            filters.add(Map.of("term", Map.of("documentId", request.documentId())));
        }
        if (request.knowledgeBaseId() != null) {
            filters.add(Map.of("term", Map.of("knowledgeBaseId", request.knowledgeBaseId())));
        }
        Collection<Long> allowed = request.allowedKnowledgeBaseIds();
        if (allowed != null && !allowed.isEmpty()) {
            filters.add(Map.of("terms", Map.of("knowledgeBaseId", allowed)));
        }
        if (!request.administrator()) {
            List<Map<String, Object>> access = List.of(
                    Map.of("term", Map.of("visibility", "PUBLIC")),
                    Map.of("term", Map.of("createBy", request.userId())));
            filters.add(Map.of(
                    "bool", Map.of("should", access, "minimum_should_match", 1)));
        }
        return filters;
    }

    List<String> exactIdentifiers(String query) {
        List<String> identifiers = new ArrayList<>();
        for (String token : query.split("\\s+")) {
            if (token.matches("(?i).*[a-z0-9][a-z0-9_.:/-]{2,}.*")) {
                identifiers.add(token);
            }
        }
        return identifiers.stream().distinct().limit(8).toList();
    }

    private void createIndex(String indexName) {
        String analyzer = availableAnalyzer();
        client.put()
                .uri("/" + indexName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(indexDefinition(analyzer))
                .retrieve()
                .toBodilessEntity();
    }

    private String availableAnalyzer() {
        String requested = properties.getAnalyzer();
        try {
            client.post()
                    .uri("/_analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("analyzer", requested, "text", "中文分词健康检查"))
                    .retrieve()
                    .toBodilessEntity();
            return requested;
        } catch (RuntimeException exception) {
            LOG.warn("Elasticsearch Analyzer {} 不可用，降级到 standard", requested);
            return "standard";
        }
    }

    private boolean indexExists(String indexName) {
        try {
            client.head().uri("/" + indexName).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return false;
            }
            throw new IllegalStateException("Elasticsearch 索引检查失败", exception);
        }
    }

    private void ensureAlias(String alias, String indexName, boolean writeAlias) {
        try {
            client.get().uri("/_alias/" + alias).retrieve().toBodilessEntity();
            return;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404) {
                throw new IllegalStateException("Elasticsearch Alias 检查失败", exception);
            }
        }
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("index", indexName);
        add.put("alias", alias);
        if (writeAlias) {
            add.put("is_write_index", true);
        }
        client.post()
                .uri("/_aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("actions", List.of(Map.of("add", add))))
                .retrieve()
                .toBodilessEntity();
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    /**
     * 描述一次 Bulk Upsert 中的稳定文档标识、Chunk 标识和索引源数据。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record IndexDocument(String id, long chunkId, Map<String, Object> source) {}

    /**
     * 区分 Bulk API 中成功和失败的 Chunk，避免把部分失败误标为全部成功。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record BulkIndexResult(List<Long> succeededChunkIds, Map<Long, String> failures) {}

}
