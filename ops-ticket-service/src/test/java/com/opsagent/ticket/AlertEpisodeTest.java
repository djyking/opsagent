package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 使用真实 SQL 验证告警重复投递、同标签新故障和恢复乱序。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AlertEpisodeTest {
    private final ObjectMapper json = new ObjectMapper();
    private SqlSession session;
    private JdbcTemplate jdbc;
    private AlertmanagerService service;
    private TicketService tickets;
    private TicketAuditMapper audit;
    private AlertTargetClient targets;

    @BeforeEach
    void setup() {
        var datasource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:alert-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("agent-test-schema.sql"))
                .execute(datasource);
        jdbc = new JdbcTemplate(datasource);
        Configuration config =
                new Configuration(
                        new Environment("alert", new JdbcTransactionFactory(), datasource));
        config.addMapper(AlertMapper.class);
        config.addMapper(AlertEpisodeMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        tickets = mock(TicketService.class);
        audit = mock(TicketAuditMapper.class);
        targets = mock(AlertTargetClient.class);
        when(targets.resolve(any(), anyString()))
                .thenAnswer(
                        call ->
                                new AlertTargetClient.Resolution(
                                        "MATCHED",
                                        ((JsonNode) call.getArgument(0)).path("service").asText(),
                                        "PROD",
                                        "matched"));
        var resolver = mock(AlertProvenanceResolver.class);
        when(resolver.resolve(anyString(), any(), anyString()))
                .thenReturn(TicketService.AlertProvenance.core());
        AtomicLong next = new AtomicLong(100);
        when(tickets.createFromAlert(
                        anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            Ticket ticket = new Ticket();
                            ticket.setId(next.incrementAndGet());
                            return ticket;
                        });
        service =
                new AlertmanagerService(
                        session.getMapper(AlertMapper.class),
                        tickets,
                        audit,
                        json,
                        new SimpleMeterRegistry(),
                        session.getMapper(AlertEpisodeMapper.class),
                        resolver,
                        targets,
                        true,
                        "test-token");
    }

    @AfterEach
    void close() {
        session.close();
    }

    @Test
    void duplicateDeliveryIsIdempotentAndNewEpisodeCreatesNewTicket() {
        var first = alert("firing", "2026-09-01T01:00:00Z", null);
        var created = service.process(first);
        assertThat(created.get("outcome")).isEqualTo("TICKET_CREATED");
        assertThat(service.process(first).get("outcome")).isEqualTo("DEDUPLICATED");
        var resolved = alert("resolved", "2026-09-01T01:00:00Z", "2026-09-01T01:05:00Z");
        service.process(resolved);
        service.process(resolved);
        var next = service.process(alert("firing", "2026-09-01T02:00:00Z", null));
        assertThat(next.get("ticketId")).isNotEqualTo(created.get("ticketId"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM monitor_alert_episode", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM monitor_alert_event", Integer.class))
                .isEqualTo(3);
        verify(audit, times(1))
                .history(
                        anyLong(),
                        eq(0L),
                        eq("ALERT_RESOLVED"),
                        anyString(),
                        anyString(),
                        anyString());
        verify(tickets, times(2))
                .createFromAlert(
                        anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void oldRecoveryCannotResolveNewEpisodeAndResolvedFirstDoesNotInventActiveTicket() {
        service.process(alert("firing", "2026-09-01T01:00:00Z", null));
        service.process(alert("firing", "2026-09-01T02:00:00Z", null));
        service.process(alert("resolved", "2026-09-01T01:00:00Z", "2026-09-01T01:20:00Z"));
        assertThat(jdbc.queryForObject("SELECT current_status FROM monitor_alert", String.class))
                .isEqualTo("firing");
        var resolvedFirst = alert("resolved", "2026-09-01T03:00:00Z", "2026-09-01T03:10:00Z");
        service.process(resolvedFirst);
        assertThat(service.process(alert("firing", "2026-09-01T03:00:00Z", null)).get("outcome"))
                .isEqualTo("STALE_IGNORED");
        verify(tickets, times(2))
                .createFromAlert(
                        anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void newEpisodeUsesCanonicalIdentityAndResolvedDeliveryDoesNotRebindIt() {
        doReturn(
                        new AlertTargetClient.Resolution(
                                "MATCHED", "ops-agent-service", "PROD", "matched"))
                .when(targets)
                .resolve(any(), anyString());
        var firing = alert("firing", "2026-09-01T01:00:00Z", null);
        ((ObjectNode) firing.path("labels"))
                .put("service", "opsagent-agent")
                .put("ci_code", "ops-agent-service");
        service.process(firing);
        verify(tickets)
                .createFromAlert(
                        anyString(),
                        contains("opsagent-agent"),
                        anyString(),
                        eq("ops-agent-service"),
                        anyString(),
                        argThat(origin -> "PROD".equals(origin.environment())));
        assertThat(jdbc.queryForObject("SELECT service_code FROM monitor_alert", String.class))
                .isEqualTo("ops-agent-service");
        clearInvocations(targets);
        var resolved = alert("resolved", "2026-09-01T01:00:00Z", "2026-09-01T01:05:00Z");
        ((ObjectNode) resolved.path("labels")).put("ci_code", "unknown-now");
        service.process(resolved);
        service.process(resolved);
        verifyNoInteractions(targets);
        assertThat(jdbc.queryForObject("SELECT service_code FROM monitor_alert", String.class))
                .isEqualTo("ops-agent-service");
    }

    @Test
    void unresolvedNewEpisodeRetainsOriginalEvidenceAndSpecificBindingDiagnostic() {
        doReturn(new AlertTargetClient.Resolution("ENVIRONMENT_MISMATCH", "", "", "告警环境与 CMDB 不一致"))
                .when(targets)
                .resolve(any(), anyString());
        service.process(alert("firing", "2026-09-01T01:00:00Z", null));
        verify(tickets)
                .createFromAlert(
                        anyString(),
                        contains("ops-demo-order-service"),
                        anyString(),
                        eq(""),
                        anyString(),
                        argThat(origin -> "".equals(origin.environment()) && !origin.isolated()));
        verify(audit)
                .workRecord(
                        anyLong(),
                        eq("ALERT_BINDING_PENDING"),
                        eq("告警环境与 CMDB 不一致"),
                        contains("ENVIRONMENT_MISMATCH"),
                        eq(0L));
        assertThat(jdbc.queryForObject("SELECT labels_json FROM monitor_alert", String.class))
                .contains("ops-demo-order-service");
        assertThat(jdbc.queryForObject("SELECT service_code FROM monitor_alert", String.class))
                .isEmpty();
    }

    @Test
    void invalidTimesAreRejectedInsteadOfFabricatedAsCurrentTime() {
        assertThatThrownBy(() -> service.process(alert("firing", "bad-time", null)))
                .hasMessageContaining("startsAt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM monitor_alert", Integer.class))
                .isZero();
    }

    @Test
    void visitorAlertQueryIncludesOnlyOwnOrExplicitShowcaseAndExcludesArchivedTickets() {
        jdbc.execute(
                """
                CREATE TABLE ticket(id BIGINT PRIMARY KEY,ticket_no VARCHAR(64),status VARCHAR(32),
                    owner_actor_id BIGINT,public_demo INT,deleted INT)
                """);
        jdbc.update(
                "INSERT INTO ticket"
                        + " VALUES(1,'OWN','CREATED',-10,0,0),(2,'OTHER','CREATED',-11,0,0),"
                        + "(3,'SHOWCASE','CREATED',2,1,0),(4,'ARCHIVED','CREATED',-10,0,1)");
        for (int id = 1; id <= 4; id++) {
            jdbc.update(
                    """
INSERT INTO monitor_alert(fingerprint,ticket_id,current_status,first_seen_time,last_seen_time)
VALUES(?,?,'firing',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
""",
                    "visible-" + id,
                    id);
        }
        var visible = session.getMapper(AlertMapper.class).listVisible("", -10);
        assertThat(visible).hasSize(2);
        assertThat(visible.stream().map(row -> ((Number) row.get("ticketid")).longValue()))
                .containsExactlyInAnyOrder(1L, 3L);
    }

    private ObjectNode alert(String status, String startsAt, String endsAt) {
        ObjectNode item = json.createObjectNode();
        item.put("status", status)
                .put("fingerprint", "same-label-fingerprint")
                .put("startsAt", startsAt);
        if (endsAt != null) item.put("endsAt", endsAt);
        item.putObject("labels")
                .put("alertname", "ServiceDown")
                .put("service", "ops-demo-order-service");
        item.putObject("annotations").put("summary", "test alert");
        return item;
    }
}
