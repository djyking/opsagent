package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证私有文档不会在进入 RAG Prompt 前泄露给其他普通用户。
 *
 * @author heyu
 * @since 2026/9/2
 */
class KnowledgeRepositoryPermissionTest {
    private KnowledgeRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-permission;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE knowledge_document("
                + "id BIGINT PRIMARY KEY,original_name VARCHAR(255),version INT,visibility VARCHAR(16),"
                + "review_status VARCHAR(16),create_by BIGINT,update_time TIMESTAMP,deleted TINYINT)");
        jdbc.execute("CREATE TABLE knowledge_chunk("
                + "id BIGINT PRIMARY KEY,document_id BIGINT,chunk_index INT,content VARCHAR(2000),"
                + "page_number INT)");
        jdbc.update(
                "INSERT INTO knowledge_document VALUES(1,'public.md',1,'PUBLIC','PUBLISHED',10,NOW(),0),"
                        + "(2,'private.md',1,'PRIVATE','PUBLISHED',20,NOW(),0),"
                        + "(3,'draft.md',1,'PUBLIC','DRAFT',10,NOW(),0)");
        jdbc.update(
                "INSERT INTO knowledge_chunk VALUES(1,1,0,'Redis故障',NULL),"
                        + "(2,2,0,'Redis私密密码',NULL),"
                        + "(3,3,0,'Redis未发布草稿',NULL)");
        repository = new KnowledgeRepository(jdbc, new ObjectMapper());
    }

    @Test
    void shouldHideOtherUsersPrivateDocument() {
        List<Map<String, Object>> result = repository.search("Redis", 10, 10L, false, null);

        assertThat(result).extracting(row -> ((Number) row.get("chunkid")).longValue())
                .containsExactly(1L);
    }

    @Test
    void shouldAllowOwnerAndAdministrator() {
        assertThat(repository.search("Redis", 10, 20L, false, null)).hasSize(2);
        assertThat(repository.search("Redis", 10, 10L, true, null)).hasSize(2);
    }

    @Test
    void shouldExcludeDraftEvenForAdministrator() {
        assertThat(repository.search("未发布草稿", 10, 10L, true, null)).isEmpty();
    }
}
