package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * 告警抖动产生不同 episode 时仍只允许一次自动运行；不执行真实模型。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentIncidentDedupTest {
    @Test
    void
            concurrentEpisodesShareOriginalIncidentRunWithoutRevivingUnknownOutcomeAndManualStillWorks()
                    throws Exception {
        var fixture = new AgentTestSupport();
        ObjectNode snapshot = AgentJson.object();
        snapshot.set("model", AgentJson.object().put("provider", "DEEPSEEK"));
        snapshot.set("tools", AgentTools.schemas(AgentTargets.ORDER));
        var pool = Executors.newFixedThreadPool(4);
        String original;
        try {
            var calls = new ArrayList<Callable<String>>();
            for (int i = 1; i <= 4; i++) {
                final int episode = i;
                calls.add(
                        () ->
                                fixture.store.create(
                                        "isolated-recovery",
                                        "alert:" + String.format("%064x", episode),
                                        -1,
                                        snapshot,
                                        state(2070 + episode)));
            }
            var results = pool.invokeAll(calls);
            original = results.get(0).get();
            for (var result : results) assertEquals(original, result.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, fixture.store.count(-1, false, null, null));
        assertEquals(1, fixture.store.outbox().size());
        ObjectNode stopped = fixture.store.get(original).state();
        stopped.put("tokens", 25841).put("turns", 3);
        stopped.set("modelIntent", AgentJson.object().put("callId", "original-unknown"));
        fixture.jdbc.update(
                "UPDATE agent_run SET status='NEEDS_ATTENTION',state_json=? WHERE id=?",
                stopped.toString(),
                original);
        assertEquals(
                original,
                new AgentService(fixture.store, fixture.clients)
                        .automatic(
                                AgentJson.object()
                                        .put("ownerActorId", -1)
                                        .put("environment", "ISOLATED")
                                        .put("incidentId", "same-incident")
                                        .put("ticketId", 9999)
                                        .put("episodeId", "b".repeat(64))));
        assertEquals(stopped, fixture.store.get(original).state());
        assertEquals("NEEDS_ATTENTION", fixture.store.get(original).status());
        assertEquals(1, fixture.store.outbox().size());
        assertEquals(0, fixture.clients.modelRequests.size());
        assertEquals(
                original,
                fixture.store.create(
                        "isolated-recovery", "alert:" + "c".repeat(64), -1, snapshot, state(9998)));
        String manual =
                fixture.store.create(
                        "isolated-recovery",
                        "manual:-1:explicit-new-request",
                        -1,
                        snapshot,
                        state(9997));
        assertNotEquals(original, manual);
        assertEquals(2, fixture.store.count(-1, false, null, null));
    }

    private static ObjectNode state(long ticket) {
        return AgentJson.object()
                .put("runId", UUID.randomUUID().toString())
                .put("ticketId", ticket)
                .put("incidentId", "same-incident")
                .put("targetCode", AgentTargets.ORDER)
                .put("deadline", Instant.now().plusSeconds(900).toString())
                .put("tokenBudget", 40000)
                .put("tokens", 0)
                .put("turns", 0)
                .put("toolCount", 0);
    }
}
