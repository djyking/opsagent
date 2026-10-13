package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证历史高重试计数、退避上限与终态幂等保护，不连接真实数据库。
 *
 * @author heyu
 * @since 2026/9/3
 */
class KnowledgeRetryBudgetTest {
    private JdbcTemplate jdbc;
    private KnowledgeRepository repository;

    @BeforeEach
    void prepareDatabase() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:retry-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        repository = new KnowledgeRepository(jdbc, new ObjectMapper());
        for (String table : new String[]{"knowledge_index_task", "document_parse_task"}) {
            jdbc.execute("CREATE TABLE " + table + "(id BIGINT PRIMARY KEY,document_id BIGINT,"
                    + "document_version INT,operation VARCHAR(16),status VARCHAR(32),retry_count INT,"
                    + "next_retry_time TIMESTAMP,error_message VARCHAR(1000),update_time TIMESTAMP)");
        }
        jdbc.execute("CREATE TABLE knowledge_document(id BIGINT PRIMARY KEY,status VARCHAR(32),"
                + "parse_error VARCHAR(1000),update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE knowledge_event_outbox(id BIGINT PRIMARY KEY,status VARCHAR(32),"
                + "retry_count INT,next_retry_time TIMESTAMP,last_error VARCHAR(1000),update_time TIMESTAMP)");
    }

    @Test
    void shouldCountEachFailureOnceAndStopAtMaximumAttempts() {
        insertTask("knowledge_index_task", "PROCESSING", 0);
        repository.indexTaskFailure(1L, 3, "failure", 10);
        assertTask("knowledge_index_task", "RETRYING", 1);
        assertThat(delay("knowledge_index_task")).isBetween(1, 2);

        jdbc.update("UPDATE knowledge_index_task SET status='PROCESSING',retry_count=8");
        repository.indexTaskFailure(1L, 3, "failure", 10);
        assertTask("knowledge_index_task", "RETRYING", 9);
        assertThat(delay("knowledge_index_task")).isBetween(299, 300);

        jdbc.update("UPDATE knowledge_index_task SET status='PROCESSING'");
        repository.indexTaskFailure(1L, 3, "failure", 10);
        assertTask("knowledge_index_task", "FAILED", 10);
        assertThat(deadline("knowledge_index_task")).isNull();
    }

    @Test
    void shouldClampCorruptRetryCountsBeforeArithmeticAndKeepTerminalHistory() {
        insertTask("knowledge_index_task", "PROCESSING", Integer.MAX_VALUE);
        repository.indexTaskFailure(1L, 3, "failure", 10);
        assertTask("knowledge_index_task", "FAILED", 10);
        assertThat(deadline("knowledge_index_task")).isNull();

        for (String status : new String[]{"SUCCESS", "FAILED"}) {
            jdbc.update("UPDATE knowledge_index_task SET status=?,retry_count=36,error_message='historical',"
                    + "next_retry_time=TIMESTAMP '6381-01-01 00:00:00'", status);
            Timestamp historicalDeadline = deadline("knowledge_index_task");
            repository.indexTaskFailure(1L, "late delivery", 10);
            repository.indexTaskFailure(1L, 3, "late delivery", 10);
            assertTask("knowledge_index_task", status, 36);
            assertThat(deadline("knowledge_index_task")).isEqualTo(historicalDeadline);
            assertThat(jdbc.queryForObject("SELECT error_message FROM knowledge_index_task", String.class))
                    .isEqualTo("historical");
        }
    }

    @Test
    void shouldNotFinishOrFailNewDocumentVersionWithOldWork() {
        insertTask("knowledge_index_task", "PROCESSING", 0);
        repository.indexTaskFailure(1L, 2, "old version", 10);
        repository.indexTaskSuccess(1L, 2);
        repository.indexTaskObsolete(1L, 2);
        assertTask("knowledge_index_task", "PROCESSING", 0);

        jdbc.update("UPDATE knowledge_index_task SET status='PENDING'");
        repository.indexTaskFailure(1L, 3, "superseded attempt", 10);
        repository.indexTaskSuccess(1L, 3);
        repository.indexTaskObsolete(1L, 3);
        assertTask("knowledge_index_task", "PENDING", 0);
    }

    @Test
    void shouldClaimOnlyCurrentDueTaskAndRequireExplicitResetAfterFailure() {
        insertTask("knowledge_index_task", "PENDING", 0);
        assertThat(repository.claimIndexTask(1L, 2)).isZero();
        assertThat(repository.claimIndexTask(1L, 3)).isEqualTo(1);
        assertThat(repository.claimIndexTask(1L, 3)).isZero();
        repository.indexTaskFailure(1L, 3, "last allowed attempt", 1);
        assertThat(repository.claimIndexTask(1L, 3)).isZero();
        assertTask("knowledge_index_task", "FAILED", 1);

        jdbc.update("UPDATE knowledge_index_task SET status='PENDING',retry_count=0");
        assertThat(repository.claimIndexTask(1L, 3)).isEqualTo(1);
        repository.indexTaskSuccess(1L, 3);
        assertThat(repository.claimIndexTask(1L, 3)).isZero();
        assertTask("knowledge_index_task", "SUCCESS", 0);
    }

    @Test
    void shouldBoundParseRetriesWithoutRegressingCompletedDocument() {
        insertTask("document_parse_task", "QUEUED", 0);
        jdbc.update("INSERT INTO knowledge_document(id,status) VALUES(1001,'PARSING')");
        repository.taskAttemptFailed(1L, 1001L, "parse failure", 3);
        assertTask("document_parse_task", "RETRYING", 1);
        assertThat(delay("document_parse_task")).isBetween(1, 2);

        jdbc.update("UPDATE document_parse_task SET retry_count=?", Integer.MAX_VALUE);
        repository.taskAttemptFailed(1L, 1001L, "parse failure", 3);
        assertTask("document_parse_task", "FAILED", 3);
        assertThat(deadline("document_parse_task")).isNull();

        jdbc.update("UPDATE document_parse_task SET status='SUCCESS'");
        jdbc.update("UPDATE knowledge_document SET status='INDEXED',parse_error=NULL");
        repository.taskAttemptFailed(1L, 1001L, "late failure", 3);
        assertThat(jdbc.queryForObject("SELECT status FROM knowledge_document", String.class)).isEqualTo("INDEXED");
        assertThat(jdbc.queryForObject("SELECT parse_error FROM knowledge_document", String.class)).isNull();
    }

    @Test
    void shouldKeepOutboxReliableWithBoundedBackoffAndProtectSentMessages() {
        jdbc.update("INSERT INTO knowledge_event_outbox(id,status,retry_count) VALUES(1,'SENDING',?)",
                Integer.MAX_VALUE);
        repository.outboxFailed(1L, "temporary broker failure");
        assertTask("knowledge_event_outbox", "RETRYING", 30);
        assertThat(delay("knowledge_event_outbox")).isBetween(299, 300);
        jdbc.update("UPDATE knowledge_event_outbox SET status='SENT',next_retry_time=NULL");
        repository.outboxFailed(1L, "late failure");
        assertTask("knowledge_event_outbox", "SENT", 30);
        assertThat(deadline("knowledge_event_outbox")).isNull();
    }

    private void insertTask(String table, String status, int retryCount) {
        jdbc.update("INSERT INTO " + table + "(id,document_id,document_version,operation,status,retry_count)"
                + " VALUES(1,1001,3,'INDEX',?,?)", status, retryCount);
    }

    private void assertTask(String table, String status, int retryCount) {
        assertThat(jdbc.queryForObject("SELECT status FROM " + table, String.class)).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT retry_count FROM " + table, Integer.class)).isEqualTo(retryCount);
    }

    private Integer delay(String table) {
        return jdbc.queryForObject("SELECT DATEDIFF('SECOND',NOW(),next_retry_time) FROM " + table, Integer.class);
    }

    private Timestamp deadline(String table) {
        return jdbc.queryForObject("SELECT next_retry_time FROM " + table, Timestamp.class);
    }
}
