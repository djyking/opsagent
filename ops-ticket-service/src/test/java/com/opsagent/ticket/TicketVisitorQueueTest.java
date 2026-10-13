package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * @author heyu
 */
class TicketVisitorQueueTest {
    @Test
    void ownScopeIncludesUnassignedOwnedTicketAndExcludesPublicForeignCasesFromRowsAndCounts() {
        var tickets = mock(TicketService.class);
        var lifecycle = mock(EventLifecycleService.class);
        var sla = mock(SlaService.class);
        var service = new TicketQueueService(tickets, lifecycle, sla, mock(TicketActorNames.class));
        var mine = row(1, -1, null);
        var publicForeign = row(2, -2, -1L);
        when(tickets.list()).thenReturn(List.of(mine, publicForeign));
        var view =
                new EventLifecycleService.View(
                        "EVT-1",
                        1,
                        1,
                        "HANDLING",
                        true,
                        "演练确认",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        "VISITOR_DRILL");
        when(lifecycle.read(anyLong())).thenReturn(view);
        var query = new TicketQueueDtos.Query(1, 10, "", "", "", "", null, "ALL", "", "mine");
        var actor =
                new InternalActorTokens.Context(
                        -1,
                        "visitor",
                        List.of("DEMO"),
                        "test",
                        "ops-demo-order-service",
                        Instant.now().plusSeconds(60));
        try (var ignored = InternalActorAccess.open(actor)) {
            var page = service.page(query);
            assertThat(page.total()).isEqualTo(1);
            assertThat(service.summary(query).counts().total()).isEqualTo(1);
            verify(lifecycle, never()).read(2);
            var all = new TicketQueueDtos.Query(1, 10, "", "", "", "", null, "ALL", "");
            assertThat(service.page(all).total()).isEqualTo(2);
        }
    }

    private TicketDtos.View row(long id, long owner, Long assignee) {
        return new TicketDtos.View(
                id,
                "T" + id,
                "隔离演练",
                "历史工单",
                "HIGH",
                "PROCESSING",
                "EVT-" + id,
                "HANDLING",
                false,
                false,
                owner,
                assignee,
                "ops-demo-order-service",
                "ISOLATED_DRILL",
                "ISOLATED",
                owner,
                "incident-" + id,
                null,
                true,
                1,
                null,
                null);
    }
}
