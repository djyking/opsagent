package com.opsagent.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行并重试 Elasticsearch 与 Qdrant 的索引补偿任务。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class KnowledgeIndexCompensationService {
    private static final Logger LOG =
            LoggerFactory.getLogger(KnowledgeIndexCompensationService.class);
    private static final int MAXIMUM_ATTEMPTS = 10;
    private final KnowledgeRepository repository;
    private final ElasticsearchVectorStore vectorStore;
    private final QdrantVectorStore qdrantStore;
    private final KnowledgeIndexService indexService;
    private final MeterRegistry metrics;
    private final AtomicInteger pendingTaskCount;

    KnowledgeIndexCompensationService(
            KnowledgeRepository repository,
            ElasticsearchVectorStore vectorStore,
            QdrantVectorStore qdrantStore,
            KnowledgeIndexService indexService,
            MeterRegistry metrics) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.qdrantStore = qdrantStore;
        this.indexService = indexService;
        this.metrics = metrics;
        this.pendingTaskCount = metrics.gauge(
                "rag.index.task.pending", new AtomicInteger());
    }

    String process(long taskId) {
        Map<String, Object> task = repository.indexTask(taskId);
        if (task == null) {
            return "NOT_FOUND";
        }
        String status = text(task, "status");
        if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
            return status;
        }
        String operation = text(task, "operation");
        Integer version = "INDEX".equals(operation)
                ? (int) number(task, "document_version", "documentVersion") : null;
        if (repository.claimIndexTask(taskId, version) == 0) {
            Map<String, Object> current = repository.indexTask(taskId);
            return current == null ? "NOT_FOUND" : text(current, "status");
        }
        long documentId = number(task, "document_id", "documentId");
        try {
            if ("DELETE".equals(operation)) {
                vectorStore.deleteDocument(documentId);
                qdrantStore.deleteDocument(documentId);
            } else if ("INDEX".equals(operation)) {
                if (!repository.validDocumentVersion(documentId, version)) {
                    repository.indexTaskObsolete(taskId, version);
                    return "FAILED";
                }
                indexService.indexDocument(documentId);
            } else {
                throw new IllegalStateException("未知索引补偿操作：" + operation);
            }
            repository.indexTaskSuccess(taskId, version);
            return "SUCCESS";
        } catch (RuntimeException exception) {
            if (version == null) {
                repository.indexTaskFailure(taskId, exception.getMessage(), MAXIMUM_ATTEMPTS);
            } else {
                repository.indexTaskFailure(taskId, version, exception.getMessage(), MAXIMUM_ATTEMPTS);
            }
            metrics.counter("rag.index.retry", "operation", operation).increment();
            LOG.warn("知识索引补偿暂时失败，taskId={}，将按退避策略重试", taskId);
            return text(repository.indexTask(taskId), "status");
        }
    }

    @Scheduled(fixedDelayString = "${ops.knowledge.index-compensation-delay-ms:10000}")
    void retryDueTasks() {
        var taskIds = repository.dueIndexTaskIds(50);
        pendingTaskCount.set(taskIds.size());
        for (Long taskId : taskIds) {
            process(taskId);
        }
    }

    private long number(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        return 0L;
    }

    private String text(Map<String, Object> row, String key) {
        if (row == null) {
            return "NOT_FOUND";
        }
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }
}
