package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.OpsPrincipal;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/**
 * Validate actual list SQL against detail authorization on an isolated database.
 *
 * @author heyu
 * @since 2026/9/3
 */
class TicketListScopeTest {
    private SqlSession session;
    private TicketService service;
    private TicketMapper mapper;

    @BeforeEach
    void setUp() {
        var datasource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:ticket-scope-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(datasource);
        jdbc.execute(
                """
                CREATE TABLE ticket (
                    id BIGINT PRIMARY KEY, ticket_no VARCHAR(64), title VARCHAR(128),
                    description VARCHAR(255), priority VARCHAR(16), status VARCHAR(32),
                    creator_id BIGINT, assignee_id BIGINT, affected_ci_code VARCHAR(64),
                    source_type VARCHAR(32), environment VARCHAR(32), owner_actor_id BIGINT,
                    incident_id VARCHAR(64), episode_id VARCHAR(64), public_demo BOOLEAN,
                    version INT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, deleted INT DEFAULT 0)
                """);
        // Created by the viewer, assigned to the viewer, unclaimed, private, owned/public demo.
        jdbc.update(
                """
                INSERT INTO ticket(id,creator_id,assignee_id,status,owner_actor_id,public_demo)
                VALUES (1,3,8,'PROCESSING',8,FALSE),
                       (2,8,3,'PROCESSING',8,FALSE),
                       (3,8,NULL,'CREATED',8,FALSE),
                       (4,8,8,'PROCESSING',8,FALSE),
                       (5,8,8,'PROCESSING',3,FALSE),
                       (6,8,8,'PROCESSING',8,TRUE),
                       (7,3,3,'PROCESSING',3,TRUE)
                """);
        jdbc.update("UPDATE ticket SET deleted=1 WHERE id=7");
        var configuration =
                new MybatisConfiguration(
                        new Environment("ticket-scope", new JdbcTransactionFactory(), datasource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(TicketMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = session.getMapper(TicketMapper.class);
        service =
                new TicketService(
                        mapper,
                        mock(TicketAuditMapper.class),
                        mock(OutboxMapper.class),
                        new ObjectMapper(),
                        mock(SlaService.class),
                        mock(DemoTicketActorVerifier.class));
    }

    @AfterEach
    void tearDown() {
        if (session != null) session.close();
        SecurityContextHolder.clearContext();
    }

    @Test
    void userIncludesOwnedAndAssignedTickets() {
        actor("USER");
        assertScope(1L, 2L);
    }

    @Test
    void opsIncludesOwnReassignedTicketsAndTheUnclaimedQueue() {
        actor("OPS");
        assertScope(1L, 2L, 3L);
    }

    @Test
    void adminSeesEveryNondeletedTicket() {
        actor("ADMIN");
        assertScope(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void demoScopeTakesPrecedenceEvenWhenAdminRoleIsAlsoPresent() {
        actor("DEMO", "ADMIN");
        assertScope(5L, 6L);
    }

    @Test
    void demoQueueAndRecoveryReadsRetainRealTicketScopeAndDenyForeignIds() {
        actor("DEMO", "ADMIN");
        var audit = mock(TicketAuditMapper.class);
        var verifier = mock(EventRecoveryVerifier.class);
        var lifecycle =
                new EventLifecycleService(
                        service,
                        mapper,
                        audit,
                        new ObjectMapper(),
                        mock(AlertEpisodeMapper.class),
                        verifier,
                        "");
        var queue =
                new TicketQueueService(
                        service, lifecycle, mock(SlaService.class), mock(TicketActorNames.class));
        var binding =
                new EventRecoveryBindingService(
                        service,
                        mapper,
                        audit,
                        new EventRecoveryRules("PROD=PROD", "mysql,redis", 180, 5, 30, 90),
                        verifier,
                        new ObjectMapper());
        var query = new TicketQueueDtos.Query(1, 100, "", "", "", "", null, "ALL", "");
        assertThat(queue.page(query).records())
                .extracting(row -> row.ticket().id())
                .containsExactlyInAnyOrder(5L, 6L);
        assertThat(queue.summary(query).counts().total()).isEqualTo(2);
        for (long id : new long[] {5, 6}) {
            assertThat(lifecycle.read(id).allowedActions()).isEmpty();
            assertThat(binding.read(id).canEdit()).isFalse();
        }
        for (long id : new long[] {1, 2, 3, 4}) {
            assertThatThrownBy(() -> lifecycle.read(id)).hasMessageContaining("无权");
            assertThatThrownBy(() -> binding.read(id)).hasMessageContaining("无权");
        }
    }

    private void actor(String... roles) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(3, "viewer", "ticket-scope", List.of(roles)),
                                null,
                                List.of()));
    }

    private void assertScope(Long... expected) {
        assertThat(service.list())
                .extracting(TicketDtos.View::id)
                .containsExactlyInAnyOrder(expected);
        for (long id = 1; id <= 6; id++) {
            Ticket ticket = mapper.selectById(id);
            if (List.of(expected).contains(id)) {
                assertThatCode(() -> service.authorizeRead(ticket)).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> service.authorizeRead(ticket)).hasMessageContaining("无权");
            }
        }
        assertThat(mapper.selectById(7)).isNull();
    }
}
