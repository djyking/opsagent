package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.InternalActorTokens;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
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
                        json);
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
}
