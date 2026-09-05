package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 在独立 H2 数据库验证 SLA 分页、筛选和全量指标口径。
 *
 * @author heyu
 * @since 2026/9/3
 */
class SlaPaginationTest {
    private JdbcTemplate jdbc;
    private SqlSession session;
    private SlaMapper mapper;
    private SlaService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource datasource = new DriverManagerDataSource(
                "jdbc:h2:mem:sla-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa", "");
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("""
                CREATE TABLE ticket(id BIGINT PRIMARY KEY,ticket_no VARCHAR(64),title VARCHAR(128),
                    priority VARCHAR(16),status VARCHAR(32),affected_ci_code VARCHAR(64),deleted INT)
                """);
        jdbc.execute("""
                CREATE TABLE ticket_sla(id BIGINT PRIMARY KEY,ticket_id BIGINT,response_deadline TIMESTAMP,
                    resolution_deadline TIMESTAMP,response_status VARCHAR(32),resolution_status VARCHAR(32),
                    escalation_level INT)
                """);
        Configuration configuration = new Configuration(
                new Environment("sla-pagination", new JdbcTransactionFactory(), datasource));
        configuration.addMapper(SlaMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = session.getMapper(SlaMapper.class);
        service = new SlaService(mapper, null, null, new ObjectMapper(), null, null, false);
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    @Test
    void pagesBeyondTheOldTwoHundredLimitAndKeepsGlobalSummary() {
        LocalDateTime deadline = LocalDateTime.now().plusHours(1);
        for (int id = 1; id <= 235; id++) {
            insert(id, "工单 " + id, "HIGH", id % 2 == 0 ? "order" : "gateway", "RUNNING", deadline, 0);
        }
        var page = service.page(query(23, 10, "all", "", "", ""));
        assertThat(page.total()).isEqualTo(235);
        assertThat(page.pageNum()).isEqualTo(23);
        assertThat(page.records()).hasSize(10);
        assertThat(page.records()).extracting(SlaDtos.Row::id)
                .containsExactly(221L, 222L, 223L, 224L, 225L, 226L, 227L, 228L, 229L, 230L);
        var lastPage = service.page(query(999, 10, "all", "", "", ""));
        assertThat(lastPage.pageNum()).isEqualTo(24);
        assertThat(lastPage.records()).hasSize(5);
        assertThat(service.summary().counts()).isEqualTo(new SlaDtos.Counts(235, 235, 235, 235, 0, 0));
        assertThat(service.summary().services()).containsExactly("gateway", "order");
    }

    @Test
    void appliesEveryFilterBeforeCountingAndTreatsWildcardCharactersLiterally() {
        LocalDateTime deadline = LocalDateTime.now().plusHours(1);
        insert(1, "CPU 100%_busy", "HIGH", "order", "RUNNING", deadline, 0);
        insert(2, "CPU 100xxbusy", "HIGH", "order", "RUNNING", deadline, 0);
        insert(3, "CPU 100%_busy", "LOW", "order", "RUNNING", deadline, 0);
        insert(4, "CPU 100%_busy", "HIGH", "gateway", "RUNNING", deadline, 0);
        insert(5, "CPU 100%_busy", "HIGH", "order", "BREACHED", deadline, 0);
        insert(6, "CPU 100%_busy", "HIGH", "order", "RUNNING", deadline, 1);
        var query = query(1, 10, "risk", "HIGH", "order", "cpu 100%_");
        var page = service.page(query);
        assertThat(page.total()).isOne();
        assertThat(page.records()).extracting(SlaDtos.Row::id).containsExactly(1L);
        assertThat(service.summary().counts().total()).isEqualTo(5);
        assertThat(service.page(query(1, 10, "breached", "HIGH", "order", "OPS-5")).total()).isOne();
        assertThat(service.page(query(1, 10, "all", "", "", "gateway")).records())
                .extracting(SlaDtos.Row::id).containsExactly(4L);
    }

    @Test
    void retainsDashboardRiskIncludingOverdueRunningClocksButKeepsBoardRiskInFuture() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 3, 12, 0);
        insert(1, "overdue running", "HIGH", "order", "RUNNING", now.minusSeconds(1), 0);
        insert(2, "deadline now", "HIGH", "order", "RUNNING", now, 0);
        insert(3, "future risk", "HIGH", "order", "RUNNING", now.plusSeconds(1), 0);
        insert(4, "two hour edge", "HIGH", "order", "RUNNING", now.plusHours(2), 0);
        insert(5, "outside risk", "HIGH", "order", "RUNNING", now.plusHours(2).plusSeconds(1), 0);
        insert(6, "breached", "HIGH", "order", "BREACHED", now.minusHours(1), 0);
        insert(7, "completed", "HIGH", "order", "COMPLETED", now.minusHours(1), 0);
        insert(8, "deleted", "HIGH", "ignored-service", "RUNNING", now.plusMinutes(1), 1);
        assertThat(mapper.counts(now, now.plusHours(2))).isEqualTo(new SlaDtos.Counts(7, 5, 2, 4, 1, 1));
        var query = query(1, 10, "risk", "", "", "");
        assertThat(mapper.countPage(query, now, now.plusHours(2))).isEqualTo(2);
        assertThat(mapper.page(query, now, now.plusHours(2), 0))
                .extracting(SlaDtos.Row::id).containsExactly(3L, 4L);
        assertThat(mapper.services()).containsExactly("order");
    }

    @Test
    void emptyPageHasStableMetadataAndZeroSummary() {
        var page = service.page(query(5, 20, "all", "", "", ""));
        assertThat(page.records()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.pageNum()).isOne();
        assertThat(page.pageSize()).isEqualTo(20);
        assertThat(service.summary().counts()).isEqualTo(new SlaDtos.Counts(0, 0, 0, 0, 0, 0));
    }

    @Test
    void bindsDefaultPaginationAndRejectsUnboundedOrInvalidRequests() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SlaController(service)).build();
        mvc.perform(get("/api/tickets/sla/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10));
        mvc.perform(get("/api/tickets/sla/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.total").value(0))
                .andExpect(jsonPath("$.data.checkedAt").isNotEmpty());
        mvc.perform(get("/api/tickets/sla/page").param("pageSize", "1000"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tickets/sla/page").param("pageNum", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tickets/sla/page").param("view", "invalid"))
                .andExpect(status().isBadRequest());
        try (ValidatorFactory validation = Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(query(1, 10, "all", "invalid", "", "")))
                    .isNotEmpty();
        }
    }

    private SlaDtos.Query query(int pageNum, int pageSize, String view, String priority, String ci, String keyword) {
        return new SlaDtos.Query(pageNum, pageSize, view, priority, ci, keyword);
    }

    private void insert(
            long id, String title, String priority, String ci, String status, LocalDateTime deadline, int deleted) {
        jdbc.update("INSERT INTO ticket VALUES(?,?,?,?,?,?,?)", id, "OPS-" + id, title,
                priority, "PROCESSING", ci, deleted);
        jdbc.update("INSERT INTO ticket_sla VALUES(?,?,?,?,?,?,?)", id, id, deadline, deadline,
                "RUNNING", status, 0);
    }
}
