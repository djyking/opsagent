package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * @author heyu
 * @since 2026/9/3
 */
class AgentToolBudgetTest {
    @Test
    void commitsEmbeddingReservationBeforeCallAndNeverResetsOnLeaseRecovery() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("embedding-budget", -1);
        var run = fixture.store.claim();
        assertEquals(id, run.id());
        fixture.store.reserveToolAiBudget(run, "knowledge-1", 2000, 32000);
        fixture.store.assertLease(run);
        assertEquals(2000, fixture.store.get(id).state().path("tokens").asInt());
        assertThrows(
                RuntimeException.class,
                () -> fixture.store.reserveToolAiBudget(run, "knowledge-1", 2000, 32000));
        fixture.recoverLease();
        var resumed = fixture.store.claim();
        assertEquals(2000, resumed.state().path("toolAiReservations").path("knowledge-1").asInt());
        assertEquals(2000, resumed.state().path("tokens").asInt());
    }

    @Test
    void rejectsBudgetOverflowBeforeRegisteringPaidIntent() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("embedding-overflow", -1);
        var run = fixture.store.claim();
        assertThrows(
                RuntimeException.class,
                () -> fixture.store.reserveToolAiBudget(run, "large-query", 40001, 40000));
        assertEquals(0, fixture.store.get(id).state().path("tokens").asInt());
        assertFalse(fixture.store.get(id).state().path("toolAiReservations").has("large-query"));
    }

    @Test
    void newRunCeilingIsPersistedAndLegacyRunKeepsOriginalLimit() {
        var state = AgentJson.object().put("tokenBudget", 40000).put("tokens", 23000);
        assertEquals(40000, AgentRuntime.tokenLimit(state));
        assertEquals(32000, AgentRuntime.tokenLimit(AgentJson.object()));
        assertEquals(90000, AgentRuntime.tokenLimit(state.put("tokenBudget", 90000)));
        assertEquals(100000, AgentRuntime.tokenLimit(state.put("tokenBudget", 120000)));
    }
}
