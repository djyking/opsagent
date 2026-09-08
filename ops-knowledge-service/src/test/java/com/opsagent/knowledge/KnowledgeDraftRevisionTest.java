package com.opsagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 草稿修订只针对本人可修改版本，禁止覆盖正在解析或已经发布的内容。
 *
 * @author heyu
 * @since 2026/9/3
 */
class KnowledgeDraftRevisionTest {
    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final TicketAccessClient tickets = mock(TicketAccessClient.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final MockMultipartFile file =
            new MockMultipartFile(
                    "file",
                    "event.md",
                    "text/markdown",
                    "修订".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private KnowledgeService service;
    private Map<String, Object> document;

    @BeforeEach
    void setUp() {
        var principal = new OpsPrincipal(10L, "owner", "test", List.of("USER"));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        service =
                new KnowledgeService(
                        repository,
                        storage,
                        mock(DocumentParserService.class),
                        mock(DocumentParsePublisher.class),
                        mock(KnowledgeIndexService.class),
                        mock(KnowledgeIndexCompensationService.class),
                        metrics,
                        new KnowledgeProperties(),
                        tickets);
        document =
                new HashMap<>(
                        Map.of(
                                "id",
                                8L,
                                "version",
                                2,
                                "create_by",
                                10L,
                                "review_status",
                                "REJECTED",
                                "ticket_id",
                                2059L));
        when(repository.lockDocument(8)).thenReturn(document);
        when(repository.document(8)).thenReturn(document);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        metrics.close();
    }

    @Test
    void staleOrPendingRevisionDoesNotWriteSource() {
        assertThatThrownBy(() -> service.reviseDraft(8, 1, file))
                .isInstanceOf(BusinessException.class);
        when(repository.parsePending(8)).thenReturn(true);
        assertThatThrownBy(() -> service.reviseDraft(8, 2, file))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);
        verify(repository, never()).reviseDraft(anyLong(), any());
    }

    @Test
    void ownerCannotOverwritePublishedOrOtherOwnersDocument() {
        document.put("review_status", "PUBLISHED");
        assertThatThrownBy(() -> service.reviseDraft(8, 2, file)).hasMessageContaining("草稿");
        document.put("review_status", "DRAFT");
        document.put("create_by", 20L);
        assertThatThrownBy(() -> service.reviseDraft(8, 2, file)).hasMessageContaining("创建者");
        verifyNoInteractions(storage);
    }

    @Test
    void savesSameDocumentWithReviewTrailAndOriginalTicketScope() throws Exception {
        var stored =
                new FileStorageService.StoredFile("event.md", "revised/event.md", "md", 6, "hash");
        when(storage.store(file)).thenReturn(stored);
        service.reviseDraft(8, 2, file);
        verify(tickets).requireVisible(2059);
        verify(repository).reviseDraft(8, stored);
        verify(repository)
                .reviewHistory(eq(8L), eq("REJECTED"), eq("DRAFT"), eq(10L), contains("3"));
        verify(repository, never()).addDocument(anyLong(), any(), any(), anyLong(), any());
    }

    @Test
    void parsingUnpublishedDraftDoesNotSendEmbeddingWork() {
        when(repository.consumeOnce("knowledge-document-parser", "local-parse")).thenReturn(1);
        assertThat(
                        service.completeParse(
                                "local-parse",
                                9L,
                                new KnowledgeService.ParsedDocument(8L, List.of())))
                .isTrue();
        verify(repository).taskSuccess(9L);
        verify(repository, never()).createIndexTaskAndOutbox(anyLong(), anyInt(), anyString());
    }

    @Test
    void approvalMustReindexPublishedMetadataBeforeShowingSearchable() {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:draft-index-"
                                        + java.util.UUID.randomUUID()
                                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                "CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,version INT,status"
                    + " VARCHAR(20),review_status VARCHAR(20),index_status VARCHAR(20),reviewer_id"
                    + " BIGINT,review_time TIMESTAMP,publish_time TIMESTAMP,review_comment"
                    + " VARCHAR(1000),update_time TIMESTAMP,deleted INT)");
        jdbc.update(
                "INSERT INTO"
                    + " knowledge_document(id,version,status,review_status,index_status,deleted)"
                    + " VALUES(8,2,'INDEXED','IN_REVIEW','SUCCESS',0)");
        var repo = new KnowledgeRepository(jdbc, new ObjectMapper());
        assertThat(repo.validDocumentVersion(8, 2)).isFalse();
        assertThat(repo.approveReview(8, 10, "read current source", 2)).isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT index_status FROM knowledge_document WHERE id=8",
                                String.class))
                .isEqualTo("PENDING");
        assertThat(repo.validDocumentVersion(8, 2)).isTrue();
        assertThat(repo.validDocumentVersion(8, 1)).isFalse();
    }
}
