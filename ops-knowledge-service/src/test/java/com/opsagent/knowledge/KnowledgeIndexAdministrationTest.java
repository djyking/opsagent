package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 回归已删除历史任务、旧版本重试、并发幂等和真实 ID 一致性核对。
 *
 * @author heyu
 * @since 2026/9/3
 */
class KnowledgeIndexAdministrationTest {
    private JdbcTemplate jdbc;
    private KnowledgeRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:index-admin;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,original_name VARCHAR(255),"
                + "version INT,deleted TINYINT,status VARCHAR(16),review_status VARCHAR(16),"
                + "index_status VARCHAR(16),update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT)");
        jdbc.execute("CREATE TABLE knowledge_index_task(id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,"
                + "document_version INT,operation VARCHAR(16),status VARCHAR(16),retry_count INT,"
                + "next_retry_time TIMESTAMP,error_message VARCHAR(1000),create_time TIMESTAMP,"
                + "update_time TIMESTAMP,UNIQUE(document_id,operation))");
        jdbc.execute("CREATE TABLE knowledge_event_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(36),"
                + "event_type VARCHAR(128),payload VARCHAR(4000),status VARCHAR(16),retry_count INT,"
                + "next_retry_time TIMESTAMP,create_time TIMESTAMP,update_time TIMESTAMP)");
        repository = new KnowledgeRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void shouldClassifyDeletedStaleDraftAndRecoveredTasksWithoutDiscardingHistory() {
        document(1034, 1, 1, "PUBLISHED", "FAILED");
        document(2, 2, 0, "PUBLISHED", "FAILED");
        document(3, 1, 0, "DRAFT", "FAILED");
        document(4, 1, 0, "PUBLISHED", "SUCCESS");
        document(5, 1, 0, "PUBLISHED", "FAILED");
        for (long id : List.of(1034L, 2L, 3L, 4L, 5L, 99L)) failedTask(id, 1, "INDEX");

        var tasks = repository.failedIndexTasks();
        assertThat(tasks).hasSize(6);
        assertThat(tasks.stream().filter(KnowledgeIndexTaskView::repairable).toList())
                .extracting(KnowledgeIndexTaskView::documentId).containsExactly(5L);
        assertThat(tasks.stream().filter(task -> task.documentId() == 1034).findFirst().orElseThrow())
                .satisfies(task -> {
                    assertThat(task.documentDeleted()).isTrue();
                    assertThat(task.repairReason()).contains("已删除");
                });
        assertThat(tasks.stream().filter(task -> task.documentId() == 2).findFirst().orElseThrow().repairReason())
                .contains("旧版本");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_task", Integer.class)).isEqualTo(6);
    }

    @Test
    void shouldRejectDeletedAndOldVersionRepairWithoutQueueingAnything() {
        document(1034, 1, 1, "PUBLISHED", "FAILED");
        long deletedTask = failedTask(1034, 1, "INDEX");
        document(2, 2, 0, "PUBLISHED", "FAILED");
        long oldTask = failedTask(2, 1, "INDEX");

        assertThatThrownBy(() -> repository.reserveIndexRepair(1034, "v1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已删除");
        assertThatThrownBy(() -> repository.retryFailedIndexTask(deletedTask, 1, "INDEX", "v1"))
                .hasMessageContaining("已删除");
        assertThatThrownBy(() -> repository.retryFailedIndexTask(oldTask, 1, "INDEX", "v1"))
                .hasMessageContaining("旧版本");
        assertThatThrownBy(() -> repository.retryFailedIndexTask(oldTask, 2, "INDEX", "v1"))
                .hasMessageContaining("版本或操作已变化");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_event_outbox", Integer.class)).isZero();
    }

    @Test
    void shouldRequirePublishedParsedChunksAndSupportCurrentVersionManualRepair() {
        document(2, 2, 0, "PUBLISHED", "FAILED");
        failedTask(2, 1, "INDEX");
        long current = repository.reserveIndexRepair(2, "v1");
        assertThat(repository.indexTask(current).get("document_version")).isEqualTo(2);
        document(3, 1, 0, "DRAFT", "FAILED");
        document(4, 1, 0, "PUBLISHED", "FAILED");
        jdbc.update("DELETE FROM knowledge_chunk WHERE document_id=4");
        assertThatThrownBy(() -> repository.reserveIndexRepair(3, "v1")).hasMessageContaining("未发布");
        assertThatThrownBy(() -> repository.reserveIndexRepair(4, "v1")).hasMessageContaining("切片");
    }

    @Test
    void shouldRetryDeletedDocumentCleanupButNeverDeleteRepublishedDocumentIndex() {
        document(1, 1, 1, "PUBLISHED", "FAILED");
        document(2, 1, 0, "PUBLISHED", "SUCCESS");
        long deleted = failedTask(1, 0, "DELETE");
        long republished = failedTask(2, 0, "DELETE");
        assertThat(repository.failedIndexTasks().stream().filter(KnowledgeIndexTaskView::repairable).toList())
                .extracting(KnowledgeIndexTaskView::id).containsExactly(deleted);
        assertThat(repository.retryFailedIndexTask(deleted, 0, "DELETE", "v1")).isEqualTo(deleted);
        assertThat(repository.retryFailedIndexTask(deleted, 0, "DELETE", "v1")).isEqualTo(deleted);
        assertThat(repository.indexTask(deleted).get("status")).isEqualTo("PENDING");
        assertThatThrownBy(() -> repository.retryFailedIndexTask(republished, 0, "DELETE", "v1"))
                .hasMessageContaining("不能重放");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_event_outbox", Integer.class)).isZero();
    }

    @Test
    void shouldReuseActiveTaskForConcurrentRepairRequests() throws Exception {
        document(1, 1, 0, "PUBLISHED", "FAILED");
        long failed = failedTask(1, 1, "INDEX");
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timed out");
                return transaction.execute(status -> repository.reserveIndexRepair(1, "v1"));
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task : tasks) assertThat(task.get(10, TimeUnit.SECONDS)).isEqualTo(failed);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_event_outbox", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void shouldFindMissingAndOrphanIdsEvenWhenBothStoreCountsMatch() {
        var es = mock(ElasticsearchVectorStore.class);
        var vectors = mock(QdrantVectorStore.class);
        var embedding = mock(EmbeddingClient.class);
        when(embedding.model()).thenReturn("test-model");
        when(es.physicalIndex()).thenReturn("es-physical");
        when(vectors.physicalCollection()).thenReturn("qdrant-physical");
        when(es.indexedDocumentIds("es-physical")).thenReturn(Set.of(1L, 99L));
        when(vectors.pointIds("qdrant-physical")).thenReturn(Set.of("10", "999"));
        when(es.indexedDocumentIds("es-physical", true)).thenReturn(Set.of(1L, 99L));
        when(vectors.pointIds("qdrant-physical", true)).thenReturn(Set.of("10", "999"));
        var index = new KnowledgeIndexService(new VectorProperties(), embedding, es, vectors, repository,
                new ObjectMapper(), mock(TokenCounter.class), mock(QueryNormalizer.class), new SimpleMeterRegistry());

        var snapshot = index.consistencySnapshot(
                Set.of(1L, 2L), Set.of("10", "20"), Set.of(1L, 2L), Set.of("10", "20"));
        assertThat(snapshot).containsEntry("indexedDocumentCount", 2).containsEntry("vectorPointCount", 2)
                .containsEntry("missingEsDocumentCount", 1L).containsEntry("orphanEsDocumentCount", 1L)
                .containsEntry("missingQdrantPointCount", 1L).containsEntry("orphanQdrantPointCount", 1L);
    }

    @Test
    void shouldEnforceAdminPermissionBeforeReadingOrRetryingTasks() {
        var repo = mock(KnowledgeRepository.class);
        var principal = new OpsPrincipal(1L, "demo", "token", List.of("OPS"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        var service = new KnowledgeService(repo, mock(FileStorageService.class), mock(DocumentParserService.class),
                mock(DocumentParsePublisher.class), mock(KnowledgeIndexService.class),
                mock(KnowledgeIndexCompensationService.class),
                new SimpleMeterRegistry(), new KnowledgeProperties(), mock(TicketAccessClient.class));
        assertThatThrownBy(service::failedIndexTasks)
                .isInstanceOf(BusinessException.class).hasMessageContaining("ADMIN");
        assertThatThrownBy(() -> service.retryFailedIndexTask(1, 1, "INDEX")).hasMessageContaining("ADMIN");
        verifyNoInteractions(repo);
    }

    @Test
    void shouldAllowDraftPreindexButDetectMissingPublishedMetadata() {
        var es = mock(ElasticsearchVectorStore.class);
        var vectors = mock(QdrantVectorStore.class);
        var embedding = mock(EmbeddingClient.class);
        when(embedding.model()).thenReturn("test-model");
        when(es.physicalIndex()).thenReturn("es");
        when(vectors.physicalCollection()).thenReturn("vectors");
        when(es.indexedDocumentIds("es")).thenReturn(Set.of(1L, 2L));
        when(vectors.pointIds("vectors")).thenReturn(Set.of("10", "20"));
        when(es.indexedDocumentIds("es", true)).thenReturn(Set.of());
        when(vectors.pointIds("vectors", true)).thenReturn(Set.of());
        var index = new KnowledgeIndexService(new VectorProperties(), embedding, es, vectors, repository,
                new ObjectMapper(), mock(TokenCounter.class), mock(QueryNormalizer.class), new SimpleMeterRegistry());
        // 文档 2 仍为合法草稿，文档 1 已发布但索引的发布标记尚未同步。
        var snapshot = index.consistencySnapshot(Set.of(1L), Set.of("10"), Set.of(1L, 2L), Set.of("10", "20"));
        assertThat(snapshot).containsEntry("missingEsDocumentCount", 1L).containsEntry("missingQdrantPointCount", 1L)
                .containsEntry("orphanEsDocumentCount", 0L).containsEntry("orphanQdrantPointCount", 0L);
    }

    @Test
    void shouldRestrictDemoDocumentMetadataToPublishedPublicKnowledge() {
        var repo = mock(KnowledgeRepository.class);
        var access = mock(TicketAccessClient.class);
        var principal = new OpsPrincipal(-99L, "demo-session", "token", List.of("DEMO"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        var publicDocument = Map.<String, Object>of("id", 1L, "visibility", "PUBLIC", "review_status", "PUBLISHED",
                "parse_error", "internal-error", "content_hash", "hash");
        when(repo.documents(1L, true)).thenReturn(List.of(publicDocument));
        when(repo.ticketDocuments(5L, -99L, false)).thenReturn(List.of(publicDocument,
                Map.of("id", 2L, "visibility", "PUBLIC", "review_status", "DRAFT")));
        var service = new KnowledgeService(repo, mock(FileStorageService.class), mock(DocumentParserService.class),
                mock(DocumentParsePublisher.class), mock(KnowledgeIndexService.class),
                mock(KnowledgeIndexCompensationService.class),
                new SimpleMeterRegistry(), new KnowledgeProperties(), access);
        service.bases();
        verify(repo).publicBases();
        assertThat(service.documents(1L)).hasSize(1)
                .allSatisfy(row -> assertThat(row).doesNotContainKeys("parse_error", "content_hash"));
        assertThat(service.ticketDocuments(5L)).hasSize(1).allSatisfy(row -> assertThat(row.get("id")).isEqualTo(1L));
        verify(access).requireVisible(5L);
        verify(repo, never()).documents(1L);
    }

    private void document(long id, int version, int deleted, String review, String index) {
        jdbc.update("INSERT INTO knowledge_document VALUES(?,?,?,?,'PARSED',?,?,NOW())",
                id, "文档 " + id, version, deleted, review, index);
        jdbc.update("INSERT INTO knowledge_chunk VALUES(?,?)", id * 10, id);
    }

    private long failedTask(long documentId, int version, String operation) {
        jdbc.update("INSERT INTO knowledge_index_task(document_id,document_version,operation,status,"
                + "retry_count,error_message,create_time,update_time)"
                + " VALUES(?,?,?,'FAILED',10,'历史索引失败',NOW(),NOW())", documentId, version, operation);
        return jdbc.queryForObject("SELECT id FROM knowledge_index_task WHERE document_id=? AND operation=?",
                Long.class, documentId, operation);
    }
}
