package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * 展示可读权限不能隐式扩大为修改他人或核心工单的权限。
 *
 * @author heyu
 * @since 2026/9/3
 */
class TicketDemoScopeTest {
    private final TicketService service =
            new TicketService(
                    mock(TicketMapper.class),
                    mock(TicketAuditMapper.class),
                    mock(OutboxMapper.class),
                    new ObjectMapper(),
                    mock(SlaService.class),
                    mock(DemoTicketActorVerifier.class));
    private final InternalActorTokens.Context actor =
            new InternalActorTokens.Context(
                    -10,
                    "访客",
                    List.of("DEMO"),
                    "run-1",
                    "ops-demo-order-service",
                    Instant.now().plusSeconds(90));

    @Test
    void canWriteOnlyOwnIsolatedDrill() {
        try (var scope = InternalActorAccess.open(actor)) {
            Ticket ticket = drill(-10);
            assertThatCode(() -> service.authorizeWrite(ticket)).doesNotThrowAnyException();
            ticket.setAffectedCiCode("ops-demo-notification-service");
            assertThatCode(() -> service.authorizeWrite(ticket)).doesNotThrowAnyException();
            ticket.setEnvironment("CORE");
            assertThatThrownBy(() -> service.authorizeWrite(ticket)).hasMessageContaining("无权");
            ticket.setEnvironment("ISOLATED");
            ticket.setAffectedCiCode("ops-auth-service");
            assertThatThrownBy(() -> service.authorizeWrite(ticket)).hasMessageContaining("无权");
        }
    }

    @Test
    void publicShowcaseIsReadableButCannotBeEdited() {
        try (var scope = InternalActorAccess.open(actor)) {
            Ticket other = drill(-11);
            assertThatThrownBy(() -> service.authorizeRead(other)).hasMessageContaining("无权");
            other.setPublicDemo(true);
            assertThatCode(() -> service.authorizeRead(other)).doesNotThrowAnyException();
            assertThatThrownBy(() -> service.authorizeWrite(other)).hasMessageContaining("无权");
        }
    }

    private Ticket drill(long ownerId) {
        Ticket ticket = new Ticket();
        ticket.setOwnerActorId(ownerId);
        ticket.setCreatorId(ownerId);
        ticket.setAffectedCiCode("ops-demo-order-service");
        ticket.setSourceType("ISOLATED_DRILL");
        ticket.setEnvironment("ISOLATED");
        ticket.setPublicDemo(false);
        return ticket;
    }
}
