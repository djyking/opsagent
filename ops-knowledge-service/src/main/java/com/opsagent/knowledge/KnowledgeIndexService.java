package com.opsagent.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编排文档 Embedding、Elasticsearch BM25、Qdrant 向量写入和混合检索。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Service
public class KnowledgeIndexService {
    private final VectorProperties properties;
    private final EmbeddingClient embeddingClient;
    private final ElasticsearchVectorStore vectorStore;
    private final QdrantVectorStore qdrantStore;
    private final KnowledgeRepository repository;
    private final ObjectMapper mapper;
    private final TokenCounter tokenCounter;
    private final QueryNormalizer queryNormalizer;
    private final MeterRegistry metrics;

    KnowledgeIndexService(
            VectorProperties properties,
            EmbeddingClient embeddingClient,
            ElasticsearchVectorStore vectorStore,
            QdrantVectorStore qdrantStore,
            KnowledgeRepository repository,
            ObjectMapper mapper,
            TokenCounter tokenCounter,
            QueryNormalizer queryNormalizer,
            MeterRegistry metrics) {
        this.properties = properties;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.qdrantStore = qdrantStore;
        this.repository = repository;
        this.mapper = mapper;
        this.tokenCounter = tokenCounter;
        this.queryNormalizer = queryNormalizer;
        this.metrics = metrics;
    }

    boolean enabled() {
        return properties.isEnabled();
    }

    boolean embeddingEnabled() {
        return properties.isEnabled() && embeddingClient.configured();
    }

    void indexDocument(long documentId) {
        indexDocument(documentId, null, null, true);
    }

    int indexDocumentTo(long documentId, String targetIndex, String targetCollection) {
        return indexDocument(documentId, targetIndex, targetCollection, false);
    }

    private int indexDocument(
            long documentId,
            String targetIndex,
            String targetCollection,
            boolean updateStatus) {
        if (!embeddingEnabled()) {
            throw new IllegalStateException("Embedding Provider 尚未配置");
        }
        Map<String, Object> document = repository.document(documentId);
        if (document == null) {
            throw new IllegalArgumentException("待索引文档不存在");
        }
        List<Map<String, Object>> chunks = repository.chunks(documentId);
        String documentTitle = text(document, "original_name", "originalName");
        List<String> texts = chunks.stream()
                .map(row -> embeddingText(row, documentTitle))
                .toList();
        if (texts.isEmpty()) {
            throw new IllegalStateException("文档没有可索引切片");
        }
        if (embeddingClient.dimensions() != properties.getDimensions()) {
            throw new IllegalStateException("Embedding Provider 与索引配置维度不一致");
        }
        Map<Integer, List<Double>> vectors = new LinkedHashMap<>();
        Map<Long, String> failures = new LinkedHashMap<>();
        for (List<Integer> batch : batches(chunks, texts)) {
            try {
                EmbeddingBatchResult result = embeddingClient.embedBatch(
                        batch.stream().map(texts::get).toList());
                validateEmbeddingResult(result, batch.size());
                for (int index = 0; index < batch.size(); index++) {
                    vectors.put(batch.get(index), result.vectors().get(index));
                }
            } catch (RuntimeException exception) {
                for (Integer index : batch) {
                    failures.put(number(chunks.get(index), "id").longValue(), safeMessage(exception));
                }
            }
        }
        List<ElasticsearchVectorStore.IndexDocument> keywordDocuments = new ArrayList<>();
        List<QdrantVectorStore.IndexPoint> vectorPoints = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            Map<String, Object> chunk = chunks.get(index);
            long chunkId = number(chunk, "id").longValue();
            if (!vectors.containsKey(index)) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("chunkId", chunkId);
            source.put("documentId", documentId);
            int chunkIndex = number(chunk, "chunk_index", "chunkIndex").intValue();
            int documentVersion = number(document, "version").intValue();
            source.put("documentVersion", documentVersion);
            source.put("version", documentVersion);
            source.put("knowledgeBaseId", number(document, "knowledge_base_id", "knowledgeBaseId").longValue());
            source.put("chunkIndex", chunkIndex);
            source.put("content", text(chunk, "content"));
            source.put("documentName", text(document, "original_name", "originalName"));
            source.put("page", value(chunk, "page_number", "pageNumber"));
            Map<String, Object> metadata = metadata(chunk);
            source.put("headingPath", metadata.getOrDefault("headingPath", List.of(documentTitle)));
            source.put("pageStart", metadata.get("pageStart"));
            source.put("pageEnd", metadata.get("pageEnd"));
            source.put("blockTypes", metadata.getOrDefault("blockTypes", List.of()));
            source.put("containsCode", metadata.getOrDefault("containsCode", false));
            source.put("containsTable", metadata.getOrDefault("containsTable", false));
            source.put("sourceFormat", metadata.getOrDefault("sourceFormat", ""));
            source.put("chunkStrategyVersion", metadata.getOrDefault("chunkStrategyVersion", "legacy"));
            source.put("contentHash", text(document, "content_hash", "contentHash"));
            source.put("createTime", text(document, "create_time", "createTime"));
            source.put("updateTime", text(document, "update_time", "updateTime"));
            source.put("visibility", text(document, "visibility"));
            source.put("reviewStatus", text(document, "review_status", "reviewStatus"));
            source.put("createBy", number(document, "create_by", "createBy").longValue());
            source.put("embeddingModel", embeddingClient.model());
            source.put("embeddingDimensions", embeddingClient.dimensions());
            keywordDocuments.add(new ElasticsearchVectorStore.IndexDocument(
                    documentId + ":" + documentVersion + ":" + chunkIndex,
                    chunkId,
                    source));
            vectorPoints.add(new QdrantVectorStore.IndexPoint(
                    chunkId, vectors.get(index), new LinkedHashMap<>(source)));
        }
        List<Long> indexedChunkIds = new ArrayList<>();
        int bulkSize = Math.max(1, properties.getBulkSize());
        for (int start = 0; start < keywordDocuments.size(); start += bulkSize) {
            int end = Math.min(start + bulkSize, keywordDocuments.size());
            List<ElasticsearchVectorStore.IndexDocument> batch =
                    keywordDocuments.subList(start, end);
            List<QdrantVectorStore.IndexPoint> pointBatch = vectorPoints.subList(start, end);
            ElasticsearchVectorStore.BulkIndexResult keywordResult;
            QdrantVectorStore.BulkUpsertResult vectorResult;
            try {
                keywordResult = targetIndex == null
                        ? vectorStore.bulkIndex(batch)
                        : vectorStore.bulkIndex(targetIndex, batch);
            } catch (RuntimeException exception) {
                keywordResult = new ElasticsearchVectorStore.BulkIndexResult(
                        List.of(), batchFailures(batch, "Elasticsearch", exception));
            }
            try {
                vectorResult = targetCollection == null
                        ? qdrantStore.bulkUpsert(pointBatch)
                        : qdrantStore.bulkUpsert(targetCollection, pointBatch);
            } catch (RuntimeException exception) {
                vectorResult = new QdrantVectorStore.BulkUpsertResult(
                        List.of(), pointFailures(pointBatch, "Qdrant", exception));
            }
            failures.putAll(keywordResult.failures());
            failures.putAll(vectorResult.failures());
            Set<Long> vectorSucceeded = new HashSet<>(vectorResult.succeededChunkIds());
            keywordResult.succeededChunkIds().stream()
                    .filter(vectorSucceeded::contains)
                    .forEach(indexedChunkIds::add);
        }
        if (updateStatus) {
            repository.markIndexResults(
                    documentId, indexedChunkIds, failures, embeddingClient.model());
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("部分知识切片索引失败，失败数量=" + failures.size());
        }
        if (targetIndex == null) {
            vectorStore.deleteOlderVersions(documentId, number(document, "version").intValue());
            qdrantStore.deleteOlderVersions(documentId, number(document, "version").intValue());
        }
        return indexedChunkIds.size();
    }

    HybridSearchResult search(RetrievalRequest rawRequest) {
        String query = queryNormalizer.normalize(rawRequest.query());
        if (query.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }
        RetrievalRequest request = new RetrievalRequest(
                query,
                rawRequest.knowledgeBaseId(),
                rawRequest.documentId(),
                rawRequest.ticketId(),
                rawRequest.serviceId(),
                rawRequest.allowedKnowledgeBaseIds(),
                rawRequest.administratorPreview(),
                rawRequest.userId(),
                rawRequest.administrator(),
                rawRequest.resultSize());
        Map<String, Long> durations = new LinkedHashMap<>();
        long started = System.nanoTime();
        List<RetrievalHit> bm25 = vectorStore.bm25Search(
                query, request, Math.max(1, properties.getBm25TopK()));
        recordStage("bm25", started, bm25.size(), durations);

        List<RetrievalHit> vector = List.of();
        String degraded = null;
        if (embeddingEnabled()) {
            long embeddingStarted = System.nanoTime();
            try {
                List<Double> queryVector = embeddingClient.embed(List.of(query)).get(0);
                recordStage("embedding", embeddingStarted, 1, durations);
                long vectorStarted = System.nanoTime();
                vector = qdrantStore.vectorSearch(
                        queryVector, request, Math.max(1, properties.getVectorTopK()));
                recordStage("vector", vectorStarted, vector.size(), durations);
            } catch (RuntimeException exception) {
                degraded = "EMBEDDING_OR_VECTOR_UNAVAILABLE";
                metrics.counter("rag.retrieval.degraded", "reason", degraded).increment();
            }
        } else {
            degraded = "EMBEDDING_UNAVAILABLE";
            metrics.counter("rag.retrieval.degraded", "reason", degraded).increment();
        }

        long rrfStarted = System.nanoTime();
        List<RetrievedChunk> fused = fuse(bm25, vector, request, degraded);
        recordStage("rrf", rrfStarted, fused.size(), durations);
        String mode = vector.isEmpty() ? "BM25" : "HYBRID_RRF";
        metrics.counter("rag.retrieval", "mode", mode).increment();
        return new HybridSearchResult(
                query,
                debugFilters(request),
                fused,
                ranks(bm25),
                ranks(vector),
                fused.stream().map(RetrievedChunk::chunkId).toList(),
                Map.copyOf(durations),
                mode,
                degraded);
    }

    List<Map<String, Object>> candidateRows(HybridSearchResult result) {
        return result.candidates().stream()
                .map(candidate -> mapper.convertValue(
                        candidate, new TypeReference<Map<String, Object>>() {}))
                .toList();
    }

    int reindexAll() {
        if (!embeddingEnabled()) {
            throw new IllegalStateException("Embedding Provider 尚未配置");
        }
        int count = 0;
        for (Long documentId : repository.indexableDocumentIds()) {
            indexDocument(documentId);
            count++;
        }
        return count;
    }

    Map<String, Object> indexMetadata() {
        return Map.of(
                "embeddingModel", embeddingClient.model(),
                "indexAlias", properties.getReadAlias(),
                "writeAlias", properties.getWriteAlias(),
                "physicalIndex", vectorStore.physicalIndex(),
                "indexedDocumentCount", vectorStore.indexedDocumentCount(),
                "vectorDatabase", "Qdrant",
                "vectorAlias", properties.getQdrantAlias(),
                "physicalCollection", qdrantStore.physicalCollection(),
                "vectorPointCount", qdrantStore.pointCount());
    }

    List<RetrievedChunk> fuse(
            List<RetrievalHit> bm25,
            List<RetrievalHit> vector,
            RetrievalRequest request,
            String degraded) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int window = Math.max(1, properties.getRrfWindow());
        addRank(candidates, bm25, RetrievalChannel.BM25, window);
        addRank(candidates, vector, RetrievalChannel.VECTOR, window);
        int outputSize = Math.min(
                Math.max(1, request.resultSize()), Math.max(1, properties.getHybridCandidates()));
        String mode = vector.isEmpty() ? "BM25" : "HYBRID_RRF";
        return candidates.values().stream()
                .sorted((left, right) -> Double.compare(right.rrfScore, left.rrfScore))
                .limit(outputSize)
                .map(candidate -> candidate.toResult(mode, degraded))
                .toList();
    }

    private void addRank(
            Map<String, Candidate> candidates,
            List<RetrievalHit> hits,
            RetrievalChannel channel,
            int window) {
        int maximum = Math.min(window, hits.size());
        for (int index = 0; index < maximum; index++) {
            RetrievalHit hit = hits.get(index);
            Candidate candidate = candidates.computeIfAbsent(
                    hit.id(), ignored -> new Candidate(hit.source()));
            candidate.channels.add(channel);
            candidate.rrfScore += 1.0D / (properties.getRrfRankConstant() + index + 1.0D);
            if (channel == RetrievalChannel.BM25) {
                candidate.bm25Score = hit.score();
            } else {
                candidate.vectorScore = hit.score();
            }
        }
    }

    private List<Long> ranks(List<RetrievalHit> hits) {
        return hits.stream()
                .map(hit -> number(hit.source(), "chunkId").longValue())
                .toList();
    }

    private Map<String, Object> debugFilters(RetrievalRequest request) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("publishStatus", request.administratorPreview() ? "ANY" : "PUBLISHED");
        filters.put("documentId", request.documentId());
        filters.put("knowledgeBaseId", request.knowledgeBaseId());
        filters.put("allowedKnowledgeBaseIds", request.allowedKnowledgeBaseIds());
        filters.put("access", request.administrator() ? "ADMIN" : "PUBLIC_OR_OWNER");
        return filters;
    }

    private void recordStage(
            String stage,
            long started,
            int candidateCount,
            Map<String, Long> durations) {
        Duration duration = Duration.ofNanos(System.nanoTime() - started);
        durations.put(stage, duration.toMillis());
        metrics.timer("rag.retrieval.duration", "stage", stage).record(duration);
        metrics.summary("rag.retrieval.candidate.count", "stage", stage).record(candidateCount);
    }

    private Object value(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
        }
        return null;
    }

    private List<List<Integer>> batches(
            List<Map<String, Object>> chunks,
            List<String> texts) {
        int maximumSize = Math.max(1, properties.getEmbeddingBatchSize());
        int maximumTokens = Math.max(1, properties.getEmbeddingBatchMaxTokens());
        List<List<Integer>> result = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int currentTokens = 0;
        for (int index = 0; index < texts.size(); index++) {
            int tokens = number(chunks.get(index), "token_count", "tokenCount").intValue();
            if (tokens <= 0) {
                tokens = tokenCounter.count(texts.get(index));
            }
            if (!current.isEmpty()
                    && (current.size() >= maximumSize || currentTokens + tokens > maximumTokens)) {
                result.add(List.copyOf(current));
                current.clear();
                currentTokens = 0;
            }
            current.add(index);
            currentTokens += tokens;
        }
        if (!current.isEmpty()) {
            result.add(List.copyOf(current));
        }
        return result;
    }

    private void validateEmbeddingResult(EmbeddingBatchResult result, int expectedSize) {
        if (!embeddingClient.model().equals(result.model())
                || result.dimensions() != properties.getDimensions()
                || result.vectors().size() != expectedSize
                || result.vectors().stream().anyMatch(vector -> vector.size() != properties.getDimensions())) {
            throw new IllegalStateException("Embedding 返回模型、数量或维度不正确");
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private Map<Long, String> batchFailures(
            List<ElasticsearchVectorStore.IndexDocument> documents,
            String store,
            RuntimeException exception) {
        Map<Long, String> result = new LinkedHashMap<>();
        documents.forEach(document -> result.put(
                document.chunkId(), store + ": " + safeMessage(exception)));
        return result;
    }

    private Map<Long, String> pointFailures(
            List<QdrantVectorStore.IndexPoint> points,
            String store,
            RuntimeException exception) {
        Map<Long, String> result = new LinkedHashMap<>();
        points.forEach(point -> result.put(
                point.chunkId(), store + ": " + safeMessage(exception)));
        return result;
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

    private String embeddingText(Map<String, Object> chunk, String documentTitle) {
        Map<String, Object> metadata = metadata(chunk);
        Object pathValue = metadata.get("headingPath");
        String heading = pathValue instanceof List<?> path && !path.isEmpty()
                ? path.stream()
                        .map(Object::toString)
                        .reduce((left, right) -> left + " > " + right)
                        .orElse(documentTitle)
                : documentTitle;
        return "文档：" + documentTitle
                + "\n章节：" + heading
                + "\n\n正文：\n" + text(chunk, "content");
    }

    private Map<String, Object> metadata(Map<String, Object> chunk) {
        Object raw = value(chunk, "metadata_json", "metadataJson");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, value) -> converted.put(String.valueOf(key), value));
            return converted;
        }
        if (raw == null || raw.toString().isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw.toString(), new TypeReference<>() {});
        } catch (Exception exception) {
            return Map.of();
        }
    }

    /**
     * 在应用层累积两路排名和 RRF 分数，避免不同检索分数直接线性相加。
     *
     * @author heyu
     * @since 2026/9/3
     */
    private final class Candidate {
        private final Map<String, Object> source;
        private final Set<RetrievalChannel> channels = EnumSet.noneOf(RetrievalChannel.class);
        private Double bm25Score;
        private Double vectorScore;
        private double rrfScore;

        private Candidate(Map<String, Object> source) {
            this.source = new HashMap<>(source);
        }

        private RetrievedChunk toResult(String mode, String degraded) {
            return new RetrievedChunk(
                    number(source, "chunkId").longValue(),
                    number(source, "documentId").longValue(),
                    number(source, "chunkIndex").intValue(),
                    text(source, "documentName"),
                    heading(source.get("headingPath")),
                    text(source, "content"),
                    nullableInteger(source.get("pageStart")),
                    nullableInteger(source.get("pageEnd")),
                    bm25Score,
                    vectorScore,
                    rrfScore,
                    null,
                    Set.copyOf(channels),
                    mode,
                    degraded);
        }

        private String heading(Object value) {
            if (value instanceof List<?> list) {
                return list.stream().map(Object::toString).reduce((a, b) -> a + " > " + b).orElse("");
            }
            return value == null ? "" : value.toString();
        }

        private Integer nullableInteger(Object value) {
            return value == null ? null : Integer.valueOf(value.toString());
        }
    }
}
