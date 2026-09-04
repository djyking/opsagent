package com.opsagent.knowledge;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台重建版本化 Elasticsearch 索引，并在校验成功后原子切换读写 Alias。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class KnowledgeReindexService {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeReindexService.class);
    private static final String LOCK_NAME = "rag:reindex:ops_knowledge_chunk";
    private final KnowledgeRepository repository;
    private final KnowledgeIndexService indexService;
    private final ElasticsearchVectorStore vectorStore;
    private final RedissonClient redisson;
    private final MeterRegistry metrics;
    private final AtomicInteger running = new AtomicInteger();

    KnowledgeReindexService(
            KnowledgeRepository repository,
            KnowledgeIndexService indexService,
            ElasticsearchVectorStore vectorStore,
            RedissonClient redisson,
            MeterRegistry metrics) {
        this.repository = repository;
        this.indexService = indexService;
        this.vectorStore = vectorStore;
        this.redisson = redisson;
        this.metrics = metrics;
        Gauge.builder("rag.reindex.running", running, AtomicInteger::get).register(metrics);
    }

    @Scheduled(fixedDelayString = "${ops.knowledge.reindex-poll-delay-ms:5000}")
    void processPending() {
        for (Long taskId : repository.pendingReindexTasks()) {
            process(taskId);
        }
    }

    void process(long taskId) {
        RLock lock = redisson.getLock(LOCK_NAME);
        if (!lock.tryLock()) {
            return;
        }
        running.set(1);
        String targetIndex = "";
        try {
            List<Long> documentIds = repository.indexableDocumentIds();
            targetIndex = vectorStore.createVersionedIndex();
            if (repository.claimReindexTask(taskId, targetIndex, documentIds.size()) == 0) {
                return;
            }
            int success = 0;
            int failure = 0;
            int chunks = 0;
            for (Long documentId : documentIds) {
                try {
                    chunks += indexService.indexDocumentTo(documentId, targetIndex);
                    success++;
                } catch (RuntimeException exception) {
                    failure++;
                    metrics.counter("rag.reindex.failure").increment();
                    LOG.warn("Reindex 文档失败，taskId={}，documentId={}", taskId, documentId);
                }
                repository.updateReindexProgress(taskId, success, failure, chunks);
            }
            if (failure > 0) {
                throw new IllegalStateException("存在 " + failure + " 个文档重建失败，Alias 未切换");
            }
            long actualDocuments = vectorStore.documentCount(targetIndex);
            if (actualDocuments != success) {
                throw new IllegalStateException(
                        "新索引抽样校验失败，预期文档=" + success + "，实际=" + actualDocuments);
            }
            vectorStore.switchAliases(targetIndex);
            documentIds.forEach(repository::markIndexSuccess);
            repository.completeReindexTask(taskId);
            metrics.counter("rag.reindex.document").increment(success);
            metrics.counter("rag.reindex.chunk").increment(chunks);
        } catch (RuntimeException exception) {
            repository.failReindexTask(taskId, exception.getMessage());
            LOG.error("知识索引全量重建失败，taskId={}，targetIndex={}", taskId, targetIndex, exception);
        } finally {
            running.set(0);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
