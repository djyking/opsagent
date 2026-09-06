package com.opsagent.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文档公开标记不能覆盖关联工单权限，索引候选必须回查当前数据库正文和发布范围。
 *
 * @author heyu
 * @since 2026/9/3
 */
class KnowledgeLinkedTicketScopeTest {
    private final TicketAccessClient access = mock(TicketAccessClient.class);
    private final KnowledgeIndexService index = mock(KnowledgeIndexService.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private KnowledgeRepository repository;
    private KnowledgeService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:linked-"
                + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,ticket_id BIGINT,"
                + "original_name VARCHAR(255),version INT,visibility VARCHAR(16),review_status VARCHAR(16),"
                + "create_by BIGINT,update_time TIMESTAMP,deleted TINYINT)");
        jdbc.execute("CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,"
                + "chunk_index INT,content VARCHAR(2000),page_number INT)");
        jdbc.update("INSERT INTO knowledge_document VALUES"
                + "(1,NULL,'全局手册',1,'PUBLIC','PUBLISHED',10,NOW(),0),"
                + "(2,2053,'本人可见工单',1,'PUBLIC','PUBLISHED',10,NOW(),0),"
                + "(3,2054,'他人私人工单',1,'PUBLIC','PUBLISHED',10,NOW(),0),"
                + "(4,NULL,'已删除',1,'PUBLIC','PUBLISHED',10,NOW(),1),"
                + "(5,NULL,'已撤回',1,'PUBLIC','DRAFT',10,NOW(),0),"
                + "(6,NULL,'已改私有',1,'PRIVATE','PUBLISHED',10,NOW(),0)");
        for (int id = 1; id <= 6; id++) jdbc.update("INSERT INTO knowledge_chunk VALUES(?,?,0,?,NULL)",
                id, id, "Redis 当前数据库正文 " + id);
        repository = spy(new KnowledgeRepository(jdbc, new ObjectMapper()));
        service = new KnowledgeService(repository, mock(FileStorageService.class), mock(DocumentParserService.class),
                mock(DocumentParsePublisher.class), index, mock(KnowledgeIndexCompensationService.class),
                metrics, new KnowledgeProperties(), access);
        var principal = new OpsPrincipal(-99L, "visitor", "ordinary-jwt-id", List.of("DEMO"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(access.visibleTicketIds()).thenReturn(Set.of(2053L));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        metrics.close();
    }

    @Test
    void hidesInaccessibleLinkedMetadataWithOneBulkPermissionRead() {
        doReturn(List.of(Map.of("id", 1L), Map.of("id", 2L, "ticket_id", 2053L),
                Map.of("id", 3L, "ticket_id", 2054L), Map.of("id", 7L, "ticket_id", 2054L)))
                .when(repository).documents(1L, true);
        assertThat(service.documents(1)).extracting(row -> row.get("id")).containsExactly(1L, 2L);
        verify(access, times(1)).visibleTicketIds();
        verify(access, never()).requireVisible(anyLong());
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "不可访问"))
                .when(access).requireVisible(2054L);
        assertThatThrownBy(() -> service.chunks(3)).isInstanceOf(BusinessException.class);
    }

    @Test
    void filtersSqlFallbackByTicketScopeBeforeReturningEvidence() {
        assertThat(service.search("Redis", 10)).extracting(row -> ((Number) row.get("documentId")).longValue())
                .containsExactly(2L, 1L);
        verify(access, times(1)).visibleTicketIds();
    }

    @Test
    void rechecksHybridCandidatesAndReturnsDatabaseTextInsteadOfStaleIndexText() {
        when(index.enabled()).thenReturn(true);
        var marker = new RetrievedChunk(1, 1, 0, "old", "", "old", null, null,
                null, null, null, null, Set.of(), "HYBRID", null);
        var result = new HybridSearchResult("Redis", Map.of(), List.of(marker), List.of(), List.of(),
                List.of(), Map.of(), "HYBRID", null);
        when(index.search(any())).thenReturn(result);
        when(index.candidateRows(result)).thenReturn(java.util.stream.LongStream.rangeClosed(1, 6)
                .mapToObj(id -> Map.<String, Object>of("chunkId", id, "documentId", id,
                        "content", "stale private index text", "retrievalMode", "HYBRID")).toList());
        var rows = service.search("Redis", 10);
        assertThat(rows).extracting(row -> row.get("documentId")).containsExactly(1L, 2L);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("content").toString())
                .startsWith("Redis 当前数据库正文").doesNotContain("stale"));
        verify(access, times(1)).visibleTicketIds();
        verify(repository, never()).search(anyString(), anyInt(), anyLong(), anyBoolean(), any());
    }

    @Test
    void permissionServiceFailureCannotRestoreUnfilteredSqlEvidence() {
        when(access.visibleTicketIds()).thenThrow(
                new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "权限验证暂时不可用"));
        assertThatThrownBy(() -> service.search("Redis", 10))
                .isInstanceOf(BusinessException.class).hasMessageContaining("权限验证");
    }
}
