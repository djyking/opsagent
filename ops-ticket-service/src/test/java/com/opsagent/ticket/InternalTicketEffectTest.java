package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 业务侧幂等重放必须复用原结果，跨主体、参数或目标变更不能复用授权。
 *
 * @author heyu
 * @since 2026/9/3
 */
class InternalTicketEffectTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final TicketService service = mock(TicketService.class);
    private final TicketMapper tickets = mock(TicketMapper.class);
    private final AlertEpisodeMapper episodes = mock(AlertEpisodeMapper.class);
    private final InternalActorTokens.Context actor =
            new InternalActorTokens.Context(
                    -10,
                    "访客",
                    List.of("DEMO"),
                    "run-1",
                    "ops-demo-order-service",
                    Instant.now().plusSeconds(90));
    private SqlSession session;
    private InternalTicketTools tools;
    private Ticket ticket;

    @BeforeEach
    void setup() {
        var datasource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:effect-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("agent-test-schema.sql"))
                .execute(datasource);
        var config =
                new Configuration(
                        new Environment("effect", new JdbcTransactionFactory(), datasource));
        config.addMapper(AgentTicketEffectMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        tools =
                new InternalTicketTools(
                        service,
                        tickets,
                        session.getMapper(AgentTicketEffectMapper.class),
                        episodes,
                        json,
                        mock(AgentEventResultService.class));
        ticket = new Ticket();
        ticket.setId(10L);
        ticket.setVersion(1);
        ticket.setAffectedCiCode("ops-demo-order-service");
        when(tickets.lock(10)).thenReturn(ticket);
        when(service.comment(eq(10L), any()))
                .thenReturn(new TicketAuditMapper.Comment(1, 10, -10, "诊断记录", null));
    }

    @AfterEach
    void close() {
        session.close();
    }

    @Test
    void repeatDoesNotAppendAgainEvenWhenTicketVersionAdvanced() {
        var input = json.createObjectNode().put("content", "诊断记录");
        var call = new InternalTicketTools.Call("run-1", "call-1", "key-1", 1, input);
        var result = tools.write(10, "comments", call, actor);
        ticket.setVersion(2);
        assertThat(tools.write(10, "comments", call, actor).toString())
                .isEqualTo(result.toString());
        verify(service, times(1)).comment(eq(10L), any());
        var changed =
                new InternalTicketTools.Call(
                        "run-1",
                        "call-1",
                        "key-1",
                        1,
                        json.createObjectNode().put("content", "different operation payload"));
        assertThatThrownBy(() -> tools.write(10, "comments", changed, actor))
                .hasMessageContaining("幂等键");
    }

    @Test
    void wrongRunAndTargetAreRejectedBeforeBusinessWrite() {
        var input = json.createObjectNode().put("content", "test");
        var wrong = new InternalTicketTools.Call("other-run", "call-1", "key-1", 1, input);
        assertThatThrownBy(() -> tools.write(10, "comments", wrong, actor))
                .hasMessageContaining("当前运行");
        ticket.setAffectedCiCode("ops-auth-service");
        var call = new InternalTicketTools.Call("run-1", "call-1", "key-1", 1, input);
        assertThatThrownBy(() -> tools.write(10, "comments", call, actor))
                .hasMessageContaining("目标");
        verify(service, never()).comment(anyLong(), any());
    }

    @Test
    void unresolvedEpisodeCannotBeDeclaredResolved() {
        ticket.setEnvironment("ISOLATED");
        ticket.setEpisodeId("episode-1");
        var call =
                new InternalTicketTools.Call(
                        "run-1",
                        "call-1",
                        "key-1",
                        1,
                        json.createObjectNode().put("toStatus", "RESOLVED"));
        assertThatThrownBy(() -> tools.write(10, "transitions", call, actor))
                .hasMessageContaining("尚未恢复");
        verify(service, never()).transition(anyLong(), any());
    }

    @Test
    void machineResultHandoffRequiresFreshControlledEvidenceAndNeverConfirmsForPeople() {
        var audit = mock(TicketAuditMapper.class);
        var records = new ArrayList<TicketAuditMapper.WorkRecord>();
        var machine = new AgentEventResultService(tickets, audit, episodes, json);
        tools =
                new InternalTicketTools(
                        service,
                        tickets,
                        session.getMapper(AgentTicketEffectMapper.class),
                        episodes,
                        json,
                        machine);
        var admin =
                new InternalActorTokens.Context(
                        1,
                        "admin",
                        List.of("ADMIN"),
                        "run-1",
                        "ops-demo-order-service",
                        Instant.now().plusSeconds(90));
        ticket.setStatus("PROCESSING");
        ticket.setEnvironment("ISOLATED");
        ticket.setIncidentId("incident-1");
        ticket.setEpisodeId("episode-1");
        var episode =
                new AlertEpisodeMapper.Episode(
                        "episode-1",
                        1,
                        "fingerprint",
                        LocalDateTime.now(),
                        LocalDateTime.now(),
                        "resolved",
                        10L,
                        "incident-1",
                        1,
                        "ISOLATED",
                        LocalDateTime.now());
        when(episodes.lock("episode-1")).thenReturn(episode);
        when(episodes.find("episode-1")).thenReturn(episode);
        when(service.require(10)).thenReturn(ticket);
        when(tickets.touch(eq(10L), anyInt())).thenReturn(1);
        when(audit.workRecords(10)).thenAnswer(call -> List.copyOf(records));
        when(audit.workRecord(eq(10L), anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(
                        call -> {
                            records.add(
                                    new TicketAuditMapper.WorkRecord(
                                            records.size() + 1,
                                            10,
                                            call.getArgument(1),
                                            call.getArgument(2),
                                            call.getArgument(3),
                                            call.getArgument(4),
                                            LocalDateTime.now()));
                            return 1;
                        });
        when(service.transition(eq(10L), any()))
                .thenAnswer(
                        call -> {
                            ticket.setStatus("RESOLVED");
                            ticket.setVersion(ticket.getVersion() + 1);
                            return null;
                        });
        var repair =
                json.createObjectNode()
                        .put("incidentId", "incident-1")
                        .put("targetCode", "ops-demo-order-service")
                        .put("tool", "demo_flow_restore")
                        .put("approvalId", "approval-1")
                        .put("approvalHash", "c".repeat(64))
                        .put("revisionBefore", "a".repeat(64))
                        .put("revisionAfter", "b".repeat(64))
                        .put("observedAt", Instant.now().minusSeconds(5).toString());
        var business =
                json.createObjectNode()
                        .put("httpStatus", 200)
                        .put("consecutiveSuccesses", 4)
                        .put("observedAt", Instant.now().toString());
        var evidence =
                json.createObjectNode()
                        .put("incidentId", "incident-1")
                        .put("targetCode", "ops-demo-order-service")
                        .put("recoverySource", "AGENT_TOOL")
                        .put("expectedRevision", "b".repeat(64))
                        .put("episodeId", "episode-1")
                        .put("episodeStatus", "resolved");
        evidence.set("business", business);
        var result = json.createObjectNode();
        result.set("approvedRepair", repair);
        result.set("evidence", evidence);
        business.put("observedAt", Instant.now().minusSeconds(90).toString());
        assertThatThrownBy(() -> machine.validate(ticket, result)).hasMessageContaining("新鲜");
        business.put("observedAt", Instant.now().toString());
        evidence.put("recoverySource", "MANUAL");
        assertThatThrownBy(() -> machine.validate(ticket, result)).hasMessageContaining("受控");
        evidence.put("recoverySource", "AGENT_TOOL");
        repair.put("incidentId", "other-incident");
        assertThatThrownBy(() -> machine.validate(ticket, result)).hasMessageContaining("同一事件");
        repair.put("incidentId", "incident-1");
        var input = json.createObjectNode().put("toStatus", "RESOLVED");
        input.set("machineResult", result);
        var call = new InternalTicketTools.Call("run-1", "verify", "machine-key", 1, input);
        var lifecycle =
                new EventLifecycleService(
                        service,
                        tickets,
                        audit,
                        json,
                        episodes,
                        mock(EventRecoveryVerifier.class),
                        "");
        try (var scope = InternalActorAccess.open(admin)) {
            var response = tools.write(10, "transitions", call, admin);
            assertThat(ticket.getVersion()).isEqualTo(3);
            assertThat(lifecycle.read(10).stage()).isEqualTo("VERIFYING");
            assertThat(lifecycle.read(10).technical()).isNull();
            assertThat(lifecycle.read(10).business()).isNull();
            assertThat(lifecycle.read(10).closed()).isNull();
            assertThat(lifecycle.read(10).allowedActions())
                    .contains("TECH_PASS")
                    .doesNotContain("BUSINESS_CONFIRM", "CLOSE");
            assertThat(records).hasSize(1);
            assertThat(records.get(0).createBy()).isZero();
            assertThat(records.get(0).content()).contains("AI 处理结果 / 机器验证");
            assertThat(records.get(0).evidence()).contains("AI_MACHINE_RESULT", "run-1");
            assertThat(tools.write(10, "transitions", call, admin)).isEqualTo(response);
            var second =
                    new InternalTicketTools.Call(
                            "run-1", "verify-again", "machine-key-2", 3, input);
            tools.write(10, "transitions", second, admin);
            assertThat(records).hasSize(1);
            verify(service, times(1)).transition(eq(10L), any());
            verify(audit, times(1))
                    .operation(eq(10L), eq(1L), eq("EVENT_AI_RESULT"), anyString(), anyString());

            // A subsequent human result and technical confirmation are never overwritten.
            records.add(
                    new TicketAuditMapper.WorkRecord(
                            2, 10, "EVENT_RESULT", "人工处理结果", "人工证据", 1, LocalDateTime.now()));
            records.add(
                    new TicketAuditMapper.WorkRecord(
                            3, 10, "EVENT_TECH_PASS", "人工技术确认", "人工证据", 1, LocalDateTime.now()));
            machine.record(ticket, second, admin);
            assertThat(lifecycle.read(10).result().content()).isEqualTo("人工处理结果");
            assertThat(lifecycle.read(10).technical()).isNotNull();
            records.add(
                    new TicketAuditMapper.WorkRecord(
                            4, 10, "EVENT_TECH_FAIL", "人工发现仍失败", "新反证", 1, LocalDateTime.now()));
            machine.record(ticket, second, admin);
            assertThat(lifecycle.read(10).stage()).isEqualTo("HANDLING");
            assertThat(records).hasSize(4);
        }
    }
}
