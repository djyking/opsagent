package com.opsagent.ticket;

import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.OpsPrincipal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 公有演练写入口同样复核访客租约，退出后的旧 JWT 不能继续处置。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class DemoTicketActorVerifier {
    private final InternalActorTokens tokens;
    private final InternalActorAccess access;

    DemoTicketActorVerifier(
            @Value("${ops.agent.internal-secret:${OPS_AGENT_INTERNAL_SECRET:}}") String secret,
            @Value("${ops.agent.auth-url:${OPS_AUTH_INTERNAL_URL:http://localhost:8101}}")
                    String authUrl) {
        tokens = new InternalActorTokens(secret);
        access = new InternalActorAccess(tokens, authUrl);
    }

    void verify(OpsPrincipal actor, Ticket ticket) {
        var context =
                new InternalActorTokens.Context(
                        actor.userId(),
                        actor.username(),
                        actor.roles(),
                        "ticket-write-" + ticket.getId(),
                        ticket.getAffectedCiCode(),
                        Instant.now().plusSeconds(90));
        access.verify("Bearer " + tokens.issue("ticket", context), "ticket");
    }
}
