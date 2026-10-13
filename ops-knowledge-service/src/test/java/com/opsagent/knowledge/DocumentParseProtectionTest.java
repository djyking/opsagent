package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证解析授权与数据库行锁，防止重复点击、并发请求重复触发解析和向量调用。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DocumentParseProtectionTest {
    private KnowledgeRepository repository;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:parse-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,create_by BIGINT,deleted INT)");
        jdbc.execute("CREATE TABLE document_parse_task(id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,"
                + "status VARCHAR(32),retry_count INT,next_retry_time TIMESTAMP,create_time TIMESTAMP,"
                + "update_time TIMESTAMP)");
        jdbc.update("INSERT INTO knowledge_document VALUES(1,10,0)");
        repository = new KnowledgeRepository(jdbc, new ObjectMapper());
    }

    @Test
    void shouldRejectOtherOwnersAndPermitAdministrator() {
        assertThatThrownBy(() -> transaction.execute(status -> repository.reserveParseTask(1, 20, false)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本人");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_parse_task", Integer.class)).isZero();
        assertThat(transaction.execute(status -> repository.reserveParseTask(1, 20, true)).created()).isTrue();
    }

    @Test
    void shouldReserveOneTaskForConcurrentRequestsAndAllowRetryAfterFailure() throws Exception {
        var workers = Executors.newFixedThreadPool(8);
        try {
            List<Callable<KnowledgeRepository.ParseReservation>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(() -> transaction.execute(
                    status -> repository.reserveParseTask(1, 10, false)));
            var results = new ArrayList<KnowledgeRepository.ParseReservation>();
            for (var future : workers.invokeAll(calls)) results.add(future.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().map(KnowledgeRepository.ParseReservation::taskId).distinct()).hasSize(1);
            assertThat(results.stream().filter(KnowledgeRepository.ParseReservation::created)).hasSize(1);
            jdbc.update("UPDATE document_parse_task SET status='FAILED'");
            assertThat(transaction.execute(status -> repository.reserveParseTask(1, 10, false)).created()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_parse_task", Integer.class)).isEqualTo(2);
        } finally {
            workers.shutdownNow();
        }
    }
}
