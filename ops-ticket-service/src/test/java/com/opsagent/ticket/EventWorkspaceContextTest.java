package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.DemoAccessPolicy;
import com.opsagent.common.security.InternalActorTokens;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;

/**
 * 事件工作区的目标发现不扩大工单数据权限，也不放宽执行工具的目标绑定。
 *
 * @author heyu
 * @since 2026/9/3
 */
class EventWorkspaceContextTest {
    private static final String SECRET = "event-workspace-test-secret-for-internal-jwt-only";
    private final TicketMapper mapper = mock(TicketMapper.class);
    private final InternalActorTokens tokens = new InternalActorTokens(SECRET);
    private MockWebServer server;
    private InternalTicketController controller;

    @BeforeEach
    void setup() throws Exception {
        server = new MockWebServer();
        String json =
                "{\"code\":0,\"data\":{\"userId\":-10,\"active\":true,"
                        + "\"username\":\"visitor\",\"roles\":[\"DEMO\"],\"expiresAt\":\""
                        + Instant.now().plusSeconds(120)
                        + "\"}}";
        for (int i = 0; i < 8; i++) {
            server.enqueue(
                    new MockResponse().setHeader("Content-Type", "application/json").setBody(json));
        }
        server.start();
        TicketService service =
                new TicketService(
                        mapper,
                        mock(TicketAuditMapper.class),
                        mock(OutboxMapper.class),
                        new ObjectMapper(),
                        mock(SlaService.class),
                        mock(DemoTicketActorVerifier.class));
        InternalTicketTools tools =
                new InternalTicketTools(
                        service,
                        mapper,
                        mock(AgentTicketEffectMapper.class),
                        mock(AlertEpisodeMapper.class),
                        new ObjectMapper());
        controller =
                new InternalTicketController(
                        service,
                        tools,
                        mock(AlertEpisodeMapper.class),
                        SECRET,
                        server.url("/").toString());
        when(mapper.selectById(10L)).thenReturn(ticket(10, -10, false));
        when(mapper.selectById(11L)).thenReturn(ticket(11, -11, false));
        when(mapper.selectById(12L)).thenReturn(ticket(12, -11, true));
    }

    @AfterEach
    void close() throws Exception {
        server.close();
        SecurityContextHolder.clearContext();
    }

    @Test
    void targetDiscoveryPreservesOwnAndPublicReadScope() {
        String authorization = authorization("ticket", "workspace");
        assertThat(controller.workspaceContext(authorization, 10).data().affectedCiCode())
                .isEqualTo("ops-demo-notification-service");
        assertThat(controller.workspaceContext(authorization, 12).data().id()).isEqualTo(12);
        assertThatThrownBy(() -> controller.workspaceContext(authorization, 11))
                .hasMessageContaining("无权");
        assertThatThrownBy(() -> controller.workspaceContext(authorization, 99))
                .hasMessageContaining("不存在");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void discoveryTokenCannotBeUsedForTargetBoundTools() {
        assertThatThrownBy(() -> controller.ticket(authorization("ticket", "workspace"), 10))
                .hasMessageContaining("目标");
        assertThat(
                        controller
                                .ticket(
                                        authorization("ticket", "ops-demo-notification-service"),
                                        10)
                                .data()
                                .id())
                .isEqualTo(10);
    }

    @Test
    void wrongAudienceAndForgedBearerCannotReadAnyTicket() {
        assertThatThrownBy(
                        () ->
                                controller.workspaceContext(
                                        authorization("platform", "workspace"), 10))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> controller.workspaceContext("Bearer forged", 10))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void visitorWorkspaceAllowsOnlyTheFixedReadPaths() {
        assertThat(DemoAccessPolicy.allows("GET", "/api/automation/tickets/10/workspace")).isTrue();
        assertThat(DemoAccessPolicy.allows("GET", "/api/automation/summary")).isTrue();
        assertThat(DemoAccessPolicy.allows("POST", "/api/automation/tickets/10/workspace"))
                .isFalse();
        assertThat(DemoAccessPolicy.allows("GET", "/api/automation/tickets/10/raw-state"))
                .isFalse();
        assertThat(DemoAccessPolicy.allows("GET", "/internal/agent/tickets/10/workspace-context"))
                .isFalse();
    }

    private String authorization(String audience, String target) {
        return "Bearer "
                + tokens.issue(
                        audience,
                        new InternalActorTokens.Context(
                                -10,
                                "visitor",
                                List.of("DEMO"),
                                "workspace-10",
                                target,
                                Instant.now().plusSeconds(90)));
    }

    private Ticket ticket(long id, long owner, boolean shared) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setCreatorId(owner);
        ticket.setOwnerActorId(owner);
        ticket.setPublicDemo(shared);
        ticket.setAffectedCiCode("ops-demo-notification-service");
        ticket.setSourceType("ISOLATED_DRILL");
        ticket.setEnvironment("ISOLATED");
        ticket.setStatus("CREATED");
        return ticket;
    }
}
