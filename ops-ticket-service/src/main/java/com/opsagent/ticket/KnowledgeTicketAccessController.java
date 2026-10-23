package com.opsagent.ticket;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Citation revalidation receives only an audience-bound read identity, never a fabricated JWT.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class KnowledgeTicketAccessController {
    private final TicketService tickets;
    private final InternalActorAccess actors;

    KnowledgeTicketAccessController(
            TicketService tickets,
            @Value("${OPS_AGENT_INTERNAL_SECRET:}") String secret,
            @Value("${OPS_AUTH_INTERNAL_URL:${OPS_AGENT_AUTH_URL:http://ops-auth-service:8101}}")
                    String auth) {
        this.tickets = tickets;
        actors = new InternalActorAccess(new InternalActorTokens(secret), auth);
    }

    @GetMapping("/internal/knowledge/tickets/{id}")
    ApiResponse<?> visible(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id) {
        var actor = actors.verify(authorization, "ticket-knowledge");
        try (var scope = InternalActorAccess.open(actor)) {
            tickets.detail(id);
            return ApiResponse.success(Map.of("id", id));
        }
    }
}
