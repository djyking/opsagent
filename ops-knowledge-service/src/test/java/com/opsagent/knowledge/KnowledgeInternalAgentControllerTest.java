package com.opsagent.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 内部 Actor 经 Auth 复核后仍受真实知识 SQL 可见性过滤，不把后台执行等同管理员。
 *
 * @author heyu
 * @since 2026/9/3
 */
class KnowledgeInternalAgentControllerTest {
    @Test
    void shouldUseCurrentIntersectionRolesWithExistingKnowledgePermissionQueries()
            throws Exception {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:internal-knowledge-"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                "CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,original_name VARCHAR(255),"
                    + " version INT,visibility VARCHAR(16),review_status VARCHAR(16),create_by"
                    + " BIGINT,update_time TIMESTAMP,deleted TINYINT)");
        jdbc.execute(
                "CREATE TABLE knowledge_chunk(id BIGINT PRIMARY KEY,document_id BIGINT,chunk_index"
                    + " INT,content VARCHAR(2000),page_number INT)");
        jdbc.update(
                "INSERT INTO knowledge_document"
                    + " VALUES(1,'public.md',1,'PUBLIC','PUBLISHED',10,NOW(),0),"
                    + "(2,'private.md',1,'PRIVATE','PUBLISHED',20,NOW(),0),"
                    + "(3,'draft.md',1,'PUBLIC','DRAFT',10,NOW(),0)");
        jdbc.update(
                "INSERT INTO knowledge_chunk VALUES(1,1,0,'Redis公开证据',NULL),"
                        + "(2,2,0,'Redis他人私密',NULL),(3,3,0,'Redis未发布草稿',NULL)");
        jdbc.execute("ALTER TABLE knowledge_document ADD COLUMN ticket_id BIGINT");
        var service =
                new KnowledgeService(
                        new KnowledgeRepository(jdbc, new ObjectMapper()),
                        mock(FileStorageService.class),
                        mock(DocumentParserService.class),
                        mock(DocumentParsePublisher.class),
                        mock(KnowledgeIndexService.class),
                        mock(KnowledgeIndexCompensationService.class),
                        new SimpleMeterRegistry(),
                        new KnowledgeProperties(),
                        mock(TicketAccessClient.class));
        String secret = "knowledge-agent-test-secret-over-32-bytes";
        var tokens = new InternalActorTokens(secret);
        var ctx =
                new InternalActorTokens.Context(
                        10,
                        "user",
                        List.of("ADMIN", "USER"),
                        "run",
                        "demo-order",
                        Instant.now().plusSeconds(60));
        try (var auth = new MockWebServer()) {
            auth.start();
            auth.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    """
                                    {"code":0,"data":{"active":true,"userId":10,"username":"user",
                                     "roles":["USER"],"expiresAt":null}}
                                    """));
            var controller =
                    new KnowledgeInternalAgentController(service, secret, auth.url("/").toString());
            var request = new KnowledgeInternalAgentController.SearchRequest("Redis", 10);
            var result =
                    controller.search("Bearer " + tokens.issue("knowledge", ctx), request).data();
            assertThat(result)
                    .extracting(row -> ((Number) row.get("chunkId")).longValue())
                    .containsExactly(1L);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(
                            tokens.verify(auth.takeRequest().getHeader("Authorization"), "auth")
                                    .userId())
                    .isEqualTo(10);
            assertThatThrownBy(
                            () -> controller.search("Bearer " + tokens.issue("rag", ctx), request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("INTERNAL_AUTH_INVALID");
            assertThat(auth.getRequestCount()).isEqualTo(1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
