package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证附件草稿仅在授权显式范围可读，且不扩大为全局已发布知识。
 *
 * @author heyu
 * @since 2026/9/3
 */
class ScopedAttachmentRetrievalTest {
    private KnowledgeRepository repository;
    private KnowledgeService service;
    private final TicketAccessClient ticketAccess = mock(TicketAccessClient.class);
    private final KnowledgeIndexService index = mock(KnowledgeIndexService.class);
    private SimpleMeterRegistry metrics;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:scoped-attachments;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,ticket_id BIGINT,"
                + "original_name VARCHAR(255),"
                + "version INT,visibility VARCHAR(16),review_status VARCHAR(16),status VARCHAR(16),create_by BIGINT,"
                + "update_time TIMESTAMP,deleted TINYINT)");
        jdbc.execute("CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,chunk_index INT,"
                + "content VARCHAR(2000),page_number INT)");
        jdbc.update("INSERT INTO knowledge_document VALUES"
                + "(1,2053,'服务清单.md',1,'PRIVATE','DRAFT','PARSED',10,NOW(),0),"
                + "(2,2054,'另一个工单.md',1,'PRIVATE','DRAFT','PARSED',10,NOW(),0),"
                + "(3,2053,'他人私密.md',1,'PRIVATE','DRAFT','PARSED',20,NOW(),0),"
                + "(4,2053,'公开草稿.md',1,'PUBLIC','DRAFT','PARSED',20,NOW(),0),"
                + "(5,2053,'已发布.md',1,'PUBLIC','PUBLISHED','INDEXED',20,NOW(),0),"
                + "(6,2053,'已删除.md',1,'PRIVATE','DRAFT','PARSED',10,NOW(),1),"
                + "(7,2053,'尚未解析.md',1,'PRIVATE','DRAFT','UPLOADED',10,NOW(),0),"
                + "(8,2053,'已归档.md',1,'PUBLIC','ARCHIVED','INDEXED',10,NOW(),0)");
        for (int i = 1; i <= 8; i++) jdbc.update("INSERT INTO knowledge_chunk VALUES(?,?,0,?,NULL)",
                i, i, "Redis 文档 " + i + "：billing-service 是账单服务");
        repository = new KnowledgeRepository(jdbc, new ObjectMapper());
        metrics = new SimpleMeterRegistry();
        service = new KnowledgeService(repository, mock(FileStorageService.class), mock(DocumentParserService.class),
                mock(DocumentParsePublisher.class), index, mock(KnowledgeIndexCompensationService.class),
                metrics, new KnowledgeProperties(), ticketAccess);
        authenticate(10, false);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        metrics.close();
    }

    @Test
    void shouldReadOwnersParsedDraftOnlyInsideExplicitScope() {
        assertThat(service.search("目前有哪些服务？", 5, 1L, 2053L))
                .extracting(row -> ((Number) row.get("documentId")).longValue()).containsExactly(1L);
        verify(ticketAccess).requireVisible(2053L);
        verifyNoInteractions(index);
        assertThat(repository.search("Redis", 30, 10, false, null))
                .extracting(row -> ((Number) row.get("documentid")).longValue()).containsExactly(5L);
    }

    @Test
    void shouldIntersectTicketScopeAndExcludePrivateDraftsDeletedAndUnparsedDocuments() {
        var rows = service.search("Redis", 30, null, 2053L);
        assertThat(rows).extracting(row -> ((Number) row.get("documentId")).longValue()).containsExactly(5L, 1L);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("retrievalMode")).isEqualTo("TICKET_ATTACHMENTS"));
        assertThatThrownBy(() -> service.search("Redis", 5, 2L, 2053L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不在当前工单");
        assertThatThrownBy(() -> service.search("Redis", 5, 3L, 2053L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不可访问");
        assertThatThrownBy(() -> service.search("Redis", 5, 6L, 2053L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.search("Redis", 5, 7L, 2053L)).hasMessageContaining("尚未完成解析");
        assertThatThrownBy(() -> service.search("Redis", 5, 8L, 2053L)).hasMessageContaining("已归档");
    }

    @Test
    void shouldCheckLinkedTicketWhenOnlyDocumentIdIsProvidedAndDenyForgedTicketIds() {
        service.search("Redis", 5, 1L, null);
        verify(ticketAccess).requireVisible(2053L);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "工单不可访问")).when(ticketAccess).requireVisible(2053L);
        assertThatThrownBy(() -> service.search("Redis", 5, null, 2053L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("工单不可访问");
        assertThatThrownBy(() -> service.ticketDocuments(2053L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldAllowAdministratorPreviewWithinTheTicketButKeepGlobalDraftsHidden() {
        authenticate(99, true);
        assertThat(service.search("Redis", 30, null, 2053L))
                .extracting(row -> ((Number) row.get("documentId")).longValue()).containsExactly(5L, 4L, 3L, 1L);
        assertThat(repository.search("Redis", 30, 99, true, null))
                .extracting(row -> ((Number) row.get("documentid")).longValue()).containsExactly(5L);
    }

    private void authenticate(long user, boolean admin) {
        var principal = new OpsPrincipal(user, "unit-user", "unit-token", admin ? List.of("ADMIN") : List.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void shouldMatchLateChunksAndOlderAttachmentsBeforeApplyingCandidateLimit() {
        for (int i = 0; i < 305; i++) jdbc.update("INSERT INTO knowledge_chunk VALUES(?,?,?,?,NULL)",
                100 + i, 5, i + 1, "不相关的填充内容");
        jdbc.update("INSERT INTO knowledge_chunk VALUES(999,5,400,'TAIL_UNIQUE 尾部证据',NULL)");
        jdbc.update("INSERT INTO knowledge_chunk VALUES(998,1,1,'OLDER_UNIQUE 旧附件证据',NULL)");
        assertThat(service.search("TAIL_UNIQUE", 5, 5L, 2053L))
                .extracting(row -> row.get("chunkId")).containsExactly(999L);
        assertThat(service.search("OLDER_UNIQUE", 5, null, 2053L))
                .extracting(row -> row.get("chunkId")).containsExactly(998L);
    }

    @Test
    void shouldNotExposePublicDraftThroughRawChunkEndpoint() {
        assertThatThrownBy(() -> service.chunks(4L)).isInstanceOf(BusinessException.class).hasMessageContaining("无权");
        assertThatThrownBy(() -> service.chunks(3L)).isInstanceOf(BusinessException.class).hasMessageContaining("无权");
    }
}
