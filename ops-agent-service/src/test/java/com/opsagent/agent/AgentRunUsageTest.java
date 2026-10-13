package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 验证十万额度边界、历史额度及预留与实际消费分离。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentRunUsageTest {
    @Test
    void runtimeContinuesPastOldCeilingAndStopsBeforeSendingAtNewCeiling() {
        for (int charged : new int[] {45000, 100000}) {
            var fixture = new AgentTestSupport();
            String id = fixture.create("finite-100k-" + charged, -1);
            var state =
                    fixture.store
                            .get(id)
                            .state()
                            .put("tokenBudget", 100000)
                            .put("tokenBudgetMode", "LIMITED")
                            .put("tokens", charged);
            fixture.jdbc.update(
                    "UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);
            for (int i = 0;
                    i < 5
                            && fixture.clients.modelRequests.isEmpty()
                            && fixture.store.get(id).status().equals("QUEUED");
                    i++) fixture.step();
            if (charged == 45000) {
                assertEquals(1, fixture.clients.modelRequests.size());
                assertEquals(
                        55000,
                        fixture.clients.modelRequests.get(0).path("remainingTokens").asInt());
            } else {
                assertTrue(fixture.clients.modelRequests.isEmpty());
                assertEquals("BUDGET_EXCEEDED", fixture.store.get(id).status());
            }
        }
    }

    @Test
    void hundredThousandLimitIsEnforcedAndOldSnapshotsAreUnchanged() {
        var state =
                AgentJson.object()
                        .put("tokenBudget", 100000)
                        .put("tokenBudgetMode", "LIMITED")
                        .put("tokens", 99000);
        assertEquals(100000, AgentRuntime.tokenLimit(state));
        assertEquals(1000, AgentRuntime.modelRequestCapacity(state));
        assertTrue(AgentRuntime.canSpend(state, 1000));
        assertFalse(AgentRuntime.canSpend(state, 1001));
        state.put("tokens", 100000);
        assertEquals(0, AgentRuntime.modelRequestCapacity(state));
        assertFalse(AgentRuntime.canSpend(state, 1));
        state.put("tokenBudget", 40000);
        assertEquals(40000, AgentRunUsage.budget(state).path("limitTokens").asInt());
        state.put("tokenBudgetMode", "UNLIMITED").put("tokenBudget", 0);
        assertTrue(AgentRunUsage.budget(state).path("limitTokens").isNull());
        assertTrue(AgentRunUsage.budget(state).path("remainingTokens").isNull());
    }

    @Test
    void defaultAndConfigurationClampAreOneHundredThousand() {
        var fixture = new AgentTestSupport();
        var service = new AgentService(fixture.store, fixture.clients);
        assertEquals(100000, service.limits().get("maxTotalTokens"));
        ReflectionTestUtils.setField(service, "runTokenBudget", 150000);
        assertEquals(100000, service.limits().get("maxTotalTokens"));
        ReflectionTestUtils.setField(service, "runTokenBudget", 90000);
        assertEquals(90000, service.limits().get("maxTotalTokens"));
    }

    @Test
    void embeddingReservationsRemainSeparateAndUnknownActualNeverBecomesZero() {
        var state = AgentJson.object().put("tokenBudget", 100000).put("tokens", 15000);
        state.putObject("toolAiReservations").put("a", 1800).put("b", 2200);
        var embedding = AgentRunUsage.embedding(state);
        assertEquals(4000, embedding.path("reservedTokens").asInt());
        assertEquals(2, embedding.path("reservationCount").asInt());
        assertTrue(embedding.path("actualTokens").isNull());
        assertFalse(embedding.path("actualUsageKnown").asBoolean());
        assertEquals(15000, AgentRunUsage.budget(state).path("chargedTokens").asInt());
        assertEquals(85000, AgentRunUsage.budget(state).path("remainingTokens").asInt());
    }
}
