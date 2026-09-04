package com.opsagent.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;

/**
 * 执行并重试持久化的 Elasticsearch 文档删除补偿任务。
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
    private final KnowledgeIndexService indexService;
    private final MeterRegistry metrics;

    KnowledgeIndexCompensationService(
            KnowledgeRepository repository,
            ElasticsearchVectorStore vectorStore,
            KnowledgeIndexService indexService,
            MeterRegistry metrics) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.indexService = indexService;
        this.metrics = metrics;
    }

    String process(long taskId) {
        Map<String, Object> task = repository.indexTask(taskId);
        if (task == null) {
            return "NOT_FOUND";
        }
        String status = text(task, "status");
        if ("SUCCESS".equals(status)) {
            return status;
        }
        if (repository.claimIndexTask(taskId) == 0) {
            Map<String, Object> current = repository.indexTask(taskId);
            return current == null ? "NOT_FOUND" : text(current, "status");
        }
        long documentId = number(task, "document_id", "documentId");
        String operation = text(task, "operation");
        try {
            if ("DELETE".equals(operation)) {
                vectorStore.deleteDocument(documentId);
            } else if ("INDEX".equals(operation)) {
                int version = (int) number(task, "document_version", "documentVersion");
                if (!repository.validDocumentVersion(documentId, version)) {
                    throw new IllegalStateException("文档版本已失效");
                }
                indexService.indexDocument(documentId);
            } else {
                throw new IllegalStateException("未知索引补偿操作：" + operation);
            }
            repository.indexTaskSuccess(taskId);
            return "SUCCESS";
        } catch (RuntimeException exception) {
            repository.indexTaskFailure(taskId, exception.getMessage(), MAXIMUM_ATTEMPTS);
            metrics.counter("rag.index.retry", "operation", operation).increment();
            LOG.warn("ES 文档删除暂时失败，taskId={}，将按退避策略重试", taskId);
            return text(repository.indexTask(taskId), "status");
        }
    }

    @Scheduled(fixedDelayString = "${ops.knowledge.index-compensation-delay-ms:10000}")
    void retryDueTasks() {
        var taskIds = repository.dueIndexTaskIds(50);
        metrics.gauge("rag.index.task.pending", taskIds.size());
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
