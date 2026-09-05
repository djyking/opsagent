package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证知识 Outbox、索引补偿操作分派和 Alias 原子切换请求。
 *
 * @author heyu
 * @since 2026/9/3
 */
class KnowledgeIndexPipelineTest {

    @Test
    void shouldCreateIndexTaskAndOutboxInKnowledgeDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:index-pipeline;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE knowledge_index_task("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,document_version INT,"
                + "operation VARCHAR(16),status VARCHAR(32),retry_count INT,next_retry_time TIMESTAMP,"
                + "error_message VARCHAR(1000),create_time TIMESTAMP,update_time TIMESTAMP,"
                + "UNIQUE(document_id,operation))");
        jdbc.execute("CREATE TABLE knowledge_event_outbox("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(36),event_type VARCHAR(128),"
                + "payload VARCHAR(4000),status VARCHAR(16),retry_count INT,next_retry_time TIMESTAMP,"
                + "create_time TIMESTAMP,update_time TIMESTAMP)");
        KnowledgeRepository repository = new KnowledgeRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());

        long taskId = repository.createIndexTaskAndOutbox(1001L, 3, "structure-v1");

        assertThat(taskId).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_index_task", Integer.class)).isEqualTo(1);
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT event_type,payload,status FROM knowledge_event_outbox");
        assertThat(event.get("event_type")).isEqualTo("knowledge.document.index.requested");
        assertThat(event.get("payload").toString())
                .contains("\"documentId\":1001", "\"documentVersion\":3", "structure-v1");
        assertThat(event.get("status")).isEqualTo("PENDING");

        long retriedTaskId = repository.createIndexTaskAndOutbox(1001L, 4, "structure-v1");
        assertThat(retriedTaskId).isEqualTo(taskId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_index_task", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT document_version FROM knowledge_index_task WHERE id=?",
                Integer.class, taskId)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_event_outbox", Integer.class)).isEqualTo(2);
    }

    @Test
    void shouldDispatchIndexCompensationWithoutDeletingDocument() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        ElasticsearchVectorStore vectorStore = mock(ElasticsearchVectorStore.class);
        QdrantVectorStore qdrantStore = mock(QdrantVectorStore.class);
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        when(repository.indexTask(9L)).thenReturn(Map.of(
                "status", "PENDING",
                "operation", "INDEX",
                "document_id", 1001L,
                "document_version", 3));
        when(repository.claimIndexTask(9L, 3)).thenReturn(1);
        when(repository.validDocumentVersion(1001L, 3)).thenReturn(true);
        KnowledgeIndexCompensationService service = new KnowledgeIndexCompensationService(
                repository,
                vectorStore,
                qdrantStore,
                indexService,
                new SimpleMeterRegistry());

        assertThat(service.process(9L)).isEqualTo("SUCCESS");

        verify(indexService).indexDocument(1001L);
        verify(repository).indexTaskSuccess(9L, 3);
    }

    @Test
    void shouldStopObsoleteIndexTaskWithoutRecreatingDeletedDocumentIndex() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeIndexService index = mock(KnowledgeIndexService.class);
        when(repository.indexTask(8L)).thenReturn(Map.of("status", "RETRYING", "operation", "INDEX",
                "document_id", 1035L, "document_version", 1));
        when(repository.claimIndexTask(8L, 1)).thenReturn(1);
        when(repository.validDocumentVersion(1035L, 1)).thenReturn(false);
        var service = new KnowledgeIndexCompensationService(repository, mock(ElasticsearchVectorStore.class),
                mock(QdrantVectorStore.class), index, new SimpleMeterRegistry());
        assertThat(service.process(8L)).isEqualTo("FAILED");
        verify(repository).indexTaskObsolete(8L, 1);
        org.mockito.Mockito.verifyNoInteractions(index);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).indexTaskFailure(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldSwitchReadAndWriteAliasesAtomically() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"acknowledged\":true}"));
        server.start();
        try {
            VectorProperties properties = new VectorProperties();
            properties.setElasticsearchUrl(server.url("/").toString());
            ElasticsearchVectorStore store = new ElasticsearchVectorStore(
                    properties, new ObjectMapper());

            store.switchAliases("ops_knowledge_chunk_v3");

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/_aliases");
            assertThat(request.getBody().readUtf8())
                    .contains("ops_knowledge_chunk_read", "ops_knowledge_chunk_write")
                    .contains("ops_knowledge_chunk_v3", "is_write_index");
        } finally {
            server.shutdown();
        }
    }
}
