package com.opsagent.knowledge;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排文档切片 Embedding、Elasticsearch 写入和查询向量检索。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Service
public class KnowledgeIndexService {
    private final VectorProperties properties;
    private final EmbeddingClient embeddingClient;
    private final ElasticsearchVectorStore vectorStore;
    private final KnowledgeRepository repository;

    KnowledgeIndexService(
            VectorProperties properties,
            EmbeddingClient embeddingClient,
            ElasticsearchVectorStore vectorStore,
            KnowledgeRepository repository) {
        this.properties = properties;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.repository = repository;
    }

    boolean enabled() {
        return properties.isEnabled() && embeddingClient.configured();
    }

    void indexDocument(long documentId) {
        if (!enabled()) {
            return;
        }
        Map<String, Object> document = repository.document(documentId);
        if (document == null) {
            throw new IllegalArgumentException("待索引文档不存在");
        }
        List<Map<String, Object>> chunks = repository.chunks(documentId);
        List<String> texts = chunks.stream().map(row -> text(row, "content")).toList();
        if (texts.isEmpty()) {
            throw new IllegalStateException("文档没有可索引切片");
        }
        List<List<Double>> vectors = embeddingClient.embed(texts);
        List<Long> indexedChunkIds = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            Map<String, Object> chunk = chunks.get(index);
            long chunkId = number(chunk, "id").longValue();
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("chunkId", chunkId);
            source.put("documentId", documentId);
            source.put("chunkIndex", number(chunk, "chunk_index", "chunkIndex").intValue());
            source.put("content", text(chunk, "content"));
            source.put("documentName", text(document, "original_name", "originalName"));
            source.put("page", value(chunk, "page_number", "pageNumber"));
            source.put("version", number(document, "version").intValue());
            source.put("updateTime", text(document, "update_time", "updateTime"));
            source.put("visibility", text(document, "visibility"));
            source.put("createBy", number(document, "create_by", "createBy").longValue());
            source.put("embeddingModel", embeddingClient.model());
            source.put("embedding", vectors.get(index));
            vectorStore.index(documentId + "_" + source.get("chunkIndex"), source);
            indexedChunkIds.add(chunkId);
        }
        repository.markIndexed(documentId, indexedChunkIds, embeddingClient.model());
    }

    List<Map<String, Object>> search(
            String query,
            long userId,
            boolean administrator,
            int topK,
            Long documentId) {
        List<Double> queryVector = embeddingClient.embed(List.of(query)).get(0);
        return vectorStore.search(queryVector, userId, administrator, topK, documentId);
    }

    int reindexAll() {
        int count = 0;
        for (Long documentId : repository.indexableDocumentIds()) {
            indexDocument(documentId);
            count++;
        }
        return count;
    }

    private Object value(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
        }
        return null;
    }

    private String text(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        return value == null ? "" : value.toString();
    }

    private Number number(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        if (value instanceof Number number) {
            return number;
        }
        return value == null ? 0L : Double.parseDouble(value.toString());
    }
}
