package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * 历史、过期和其他现场的机器结果不得确认当前事件恢复。
 *
 * @author heyu
 * @since 2026/9/3
 */
class EventRecoveryVerifierTest {
    @Test
    void acceptsOnlyCurrentExactFreshSuccessfulWorkspaceEvidence() {
        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setIncidentId("incident-1");
        ticket.setAffectedCiCode("ops-demo-order-service");
        var data =
                new ObjectMapper()
                        .createObjectNode()
                        .put("ticketId", 1)
                        .put("incidentId", "incident-1")
                        .put("targetCode", "ops-demo-order-service")
                        .put("generatedAt", Instant.now().toString());
        var verification =
                data.putObject("verification")
                        .put("scope", "CURRENT")
                        .put("status", "RECOVERED")
                        .put("incidentMatched", true)
                        .put("businessHealthy", true)
                        .put("alertResolved", true)
                        .put("observedAt", Instant.now().toString())
                        .put("consecutiveSuccesses", 3);
        assertThat(EventRecoveryVerifier.verified(data, ticket)).isTrue();
        verification.put("consecutiveSuccesses", 2);
        assertThat(EventRecoveryVerifier.verified(data, ticket)).isFalse();
        verification
                .put("consecutiveSuccesses", 3)
                .put("observedAt", Instant.now().minusSeconds(21).toString());
        assertThat(EventRecoveryVerifier.verified(data, ticket)).isFalse();
        verification.put("observedAt", Instant.now().toString());
        verification.put("scope", "HISTORICAL");
        assertThat(EventRecoveryVerifier.verified(data, ticket)).isFalse();
        verification.put("scope", "CURRENT").put("status", "FAULT_ACTIVE");
        assertThat(EventRecoveryVerifier.verified(data, ticket)).isFalse();
        verification.put("status", "RECOVERED");
        data.put("incidentId", "other");
        assertThat(EventRecoveryVerifier.verified(data, ticket)).isFalse();
        data.put("incidentId", "incident-1")
                .put("generatedAt", Instant.now().minusSeconds(21).toString());
        assertThat(EventRecoveryVerifier.verified(data, ticket)).isFalse();
    }
}
