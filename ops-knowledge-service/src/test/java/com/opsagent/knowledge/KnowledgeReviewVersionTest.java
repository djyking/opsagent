package com.opsagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

/**
 * 审核必须针对读过的内容版本，发布不冒充索引成功。
 *
 * @author heyu
 * @since 2026/9/3
 */
class KnowledgeReviewVersionTest {
    @Test
    void rejectsStaleVersionAndKeepsIndexStateSeparate() {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:review-version-"
                                        + UUID.randomUUID()
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
                    + " VALUES(1,2,'PARSED','IN_REVIEW','PENDING',0)");
        var repository = new KnowledgeRepository(jdbc, new ObjectMapper());
        assertThat(repository.approveReview(1, 7, "read old version", 1)).isZero();
        assertThat(repository.approveReview(1, 7, "reviewed current version", 2)).isOne();
        assertThat(repository.approveReview(1, 8, "duplicate approval", 2)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT index_status FROM knowledge_document WHERE id=1",
                                String.class))
                .isEqualTo("PENDING");
    }
}
