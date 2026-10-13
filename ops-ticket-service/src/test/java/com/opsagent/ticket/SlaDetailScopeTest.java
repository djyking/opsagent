package com.opsagent.ticket;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.web.GlobalExceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SLA 单工单端点与工单详情共用读取范围；共享看板保持原有口径。
 *
 * @author heyu
 * @since 2026/9/3
 */
class SlaDetailScopeTest {
    private final TicketMapper mapper = mock(TicketMapper.class);
    private final SlaService sla = mock(SlaService.class);
    private final TicketService tickets =
            new TicketService(
                    mapper,
                    mock(TicketAuditMapper.class),
                    mock(OutboxMapper.class),
                    new ObjectMapper(),
                    sla,
                    mock(DemoTicketActorVerifier.class));
    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new SlaController(sla, tickets))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();

    @Test
    void visitorCannotReadAnotherVisitorsPrivateTicketSla() throws Exception {
        when(mapper.selectById(11L)).thenReturn(ticket(-11, false));
        try (var scope = InternalActorAccess.open(actor("DEMO"))) {
            mvc.perform(get("/api/tickets/11/sla"))
                    .andExpect(jsonPath("$.code").value(40300))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        verifyNoInteractions(sla);
    }

    @Test
    void visitorCanReadOwnTicketAndPublicShowcaseSla() throws Exception {
        when(mapper.selectById(10L)).thenReturn(ticket(-10, false));
        when(mapper.selectById(11L)).thenReturn(ticket(-11, true));
        when(sla.detail(10L)).thenReturn(Map.of("ticket_id", 10L));
        when(sla.detail(11L)).thenReturn(Map.of("ticket_id", 11L));
        try (var scope = InternalActorAccess.open(actor("DEMO"))) {
            mvc.perform(get("/api/tickets/10/sla"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.ticket_id").value(10));
            mvc.perform(get("/api/tickets/11/sla"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.ticket_id").value(11));
        }
    }

    @Test
    void administratorRetainsAccessAndMissingTicketCannotReachSla() throws Exception {
        when(mapper.selectById(11L)).thenReturn(ticket(-11, false));
        when(sla.detail(11L)).thenReturn(Map.of("ticket_id", 11L));
        try (var scope = InternalActorAccess.open(actor("ADMIN"))) {
            mvc.perform(get("/api/tickets/11/sla"))
                    .andExpect(jsonPath("$.code").value(0));
            mvc.perform(get("/api/tickets/99/sla"))
                    .andExpect(jsonPath("$.code").value(40400));
        }
        verify(sla, never()).detail(99L);
    }

    @Test
    void sharedOverviewDoesNotRequireAnIndividualTicket() throws Exception {
        when(sla.overview()).thenReturn(List.of(Map.of("ticket_id", 11L)));
        try (var scope = InternalActorAccess.open(actor("DEMO"))) {
            mvc.perform(get("/api/tickets/sla/overview"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data[0].ticket_id").value(11));
        }
        verifyNoInteractions(mapper);
    }

    private InternalActorTokens.Context actor(String role) {
        return new InternalActorTokens.Context(
                -10, "访客", List.of(role), "scope-read", "ops-demo-order-service",
                Instant.now().plusSeconds(90));
    }

    private Ticket ticket(long owner, boolean publicDemo) {
        Ticket ticket = new Ticket();
        ticket.setId(Math.abs(owner));
        ticket.setCreatorId(owner);
        ticket.setOwnerActorId(owner);
        ticket.setPublicDemo(publicDemo);
        return ticket;
    }
}
