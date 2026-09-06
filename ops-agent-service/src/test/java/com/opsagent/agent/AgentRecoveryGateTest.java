package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/**
 * @author heyu
 */
class AgentRecoveryGateTest {
    @Test
    void explicitGateWaitsForDelayedAlertAndIgnoresPrematureMqWakeWithoutMoreModelCalls() {
        var fixture = new AgentTestSupport();
        fixture.clients.model = request -> AgentTestSupport.finalResponse();
        fixture.clients.episodeStatus = "firing";
        String id = fixture.create("delayed-alert", -1);
        for (int i = 0; i < 5; i++) fixture.step();
        assertEquals("verify", fixture.store.get(id).node());
        assertEquals("QUEUED", fixture.store.get(id).status());
        assertEquals(
                "EPISODE_PENDING",
                fixture.store
                        .get(id)
                        .state()
                        .path("recoveryVerification")
                        .path("reasonCode")
                        .asText());
        assertEquals(0, fixture.clients.writes.size());
        int reads = fixture.clients.targetReads;
        fixture.store.notifyRun(id);
        fixture.step();
        assertEquals(reads, fixture.clients.targetReads);
        fixture.clients.episodeStatus = "resolved";
        fixture.elapseRecoveryDelay(id);
        fixture.finish(id);
        assertEquals("COMPLETED", fixture.store.get(id).status());
        assertTrue(fixture.store.get(id).state().path("ticketResolved").asBoolean());
        assertEquals(1, fixture.clients.modelRequests.size());
        assertEquals(
                "resolved",
                fixture.store
                        .get(id)
                        .state()
                        .path("recoveryVerification")
                        .path("evidence")
                        .path("episodeStatus")
                        .asText());
        assertTrue(
                fixture.clients
                        .writes
                        .get(0)
                        .path("input")
                        .path("comment")
                        .asText()
                        .contains("HTTP=200"));
    }

    @Test
    void exhaustedRecoveryPollsPreserveEvidenceAndNeverReportCompletion() {
        var fixture = new AgentTestSupport();
        fixture.clients.model = request -> AgentTestSupport.finalResponse();
        fixture.clients.episodeStatus = "firing";
        String id = fixture.create("exhausted-alert", -1);
        for (int i = 0; i < 5; i++) fixture.step();
        for (int i = 1; i < 12; i++) {
            fixture.elapseRecoveryDelay(id);
            fixture.step();
        }
        assertEquals("NEEDS_ATTENTION", fixture.store.get(id).status());
        assertFalse(fixture.store.get(id).state().path("ticketResolved").asBoolean());
        assertEquals(12, fixture.clients.targetReads);
        assertEquals(0, fixture.clients.writes.size());
        assertEquals(1, fixture.clients.modelRequests.size());
        assertEquals(
                "EPISODE_PENDING",
                fixture.store
                        .get(id)
                        .state()
                        .path("recoveryVerification")
                        .path("reasonCode")
                        .asText());
        fixture.store.resume(id, -1);
        fixture.step();
        assertEquals("NEEDS_ATTENTION", fixture.store.get(id).status());
        assertEquals(12, fixture.clients.targetReads);
    }

    @Test
    void threeTransitionCloseoutReplaysMiddleCommittedRequestAfterWorkerCrash() {
        var fixture = new AgentTestSupport();
        fixture.clients.model = request -> AgentTestSupport.finalResponse();
        fixture.clients.status = "CREATED";
        String id = fixture.create("three-transitions", -1);
        for (int i = 0; i < 5; i++) fixture.step();
        fixture.step();
        assertEquals("ASSIGNED", fixture.clients.status);
        fixture.step();
        JsonNode durableBody =
                fixture.store
                        .get(id)
                        .state()
                        .path("toolIntent")
                        .path("preparedRequest")
                        .path("body")
                        .deepCopy();
        assertEquals(2, durableBody.path("expectedVersion").asInt());
        fixture.clients.crashAfterWrite = true;
        assertThrows(AssertionError.class, fixture::step);
        assertEquals("PROCESSING", fixture.clients.status);
        fixture.recoverLease();
        new AgentRuntime(fixture.store, fixture.clients, new AgentTools(fixture.clients), true)
                .tick();
        fixture.finish(id);
        assertEquals("COMPLETED", fixture.store.get(id).status());
        assertEquals("RESOLVED", fixture.clients.status);
        assertEquals(4, fixture.clients.writes.size());
        assertEquals(durableBody, fixture.clients.writes.get(1));
        assertEquals(durableBody, fixture.clients.writes.get(2));
        assertEquals(3, fixture.clients.committedRequests.size());
        assertEquals(4, fixture.clients.version);
        assertEquals(3, fixture.store.get(id).state().path("toolCount").asInt());
        assertEquals(1, fixture.clients.modelRequests.size());
        assertTrue(
                fixture.clients
                        .writes
                        .get(0)
                        .path("toolCallId")
                        .asText()
                        .endsWith(":transition:0"));
        assertTrue(
                fixture.clients
                        .writes
                        .get(3)
                        .path("toolCallId")
                        .asText()
                        .endsWith(":transition:2"));
    }
}
