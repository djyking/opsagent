package com.opsagent.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    KnowledgeIndexCompensationService(
            KnowledgeRepository repository, ElasticsearchVectorStore vectorStore) {
        this.repository = repository;
        this.vectorStore = vectorStore;
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
        try {
            vectorStore.deleteDocument(documentId);
            repository.indexTaskSuccess(taskId);
            return "SUCCESS";
        } catch (RuntimeException exception) {
            repository.indexTaskFailure(taskId, exception.getMessage(), MAXIMUM_ATTEMPTS);
            LOG.warn("ES 文档删除暂时失败，taskId={}，将按退避策略重试", taskId);
            return text(repository.indexTask(taskId), "status");
        }
    }

    @Scheduled(fixedDelayString = "${ops.knowledge.index-compensation-delay-ms:10000}")
    void retryDueTasks() {
        for (Long taskId : repository.dueIndexTaskIds(50)) {
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
