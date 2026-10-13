package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * @author heyu
 */
class AgentStoreTest {
    @Test
    void workerFenceCoversModelTransportWithoutExtendingTheOriginalRunDeadline() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("bounded-model-lease", -1);
        String deadline = fixture.store.get(id).state().path("deadline").asText();
        var run = fixture.store.claim();
        Long remaining =
                fixture.jdbc.queryForObject(
                        "SELECT TIMESTAMPDIFF(SECOND,NOW(3),lease_until) FROM agent_run WHERE id=?",
                        Long.class,
                        id);
        assertNotNull(remaining);
        assertTrue(remaining > 130 && remaining <= 150);
        assertEquals(deadline, run.state().path("deadline").asText());
        assertNull(fixture.store.claim());
    }

    @Test
    void concurrentDuplicateTriggerCreatesOnlyOneRunAndWake() throws Exception {
        var fixture = new AgentTestSupport();
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) calls.add(() -> fixture.create("same-trigger", -1));
            var results = executor.invokeAll(calls);
            String first = results.get(0).get();
            for (var result : results) assertEquals(first, result.get());
            assertEquals(1, fixture.store.count(-1, false, null, null));
            assertEquals(1, fixture.store.outbox().size());
            assertThrows(BusinessException.class, () -> fixture.create("same-trigger", -2));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void ownerScopeAppliesToDetailAndFilteredPagination() {
        var fixture = new AgentTestSupport();
        String own = fixture.create("owner-one", -1);
        String other = fixture.create("owner-two", -2);
        assertEquals(1, fixture.store.count(-1, false, 7L, "incident-1"));
        assertEquals(own, fixture.store.runs(1, 12, -1, false, 7L, "incident-1").get(0).get("id"));
        assertEquals(2, fixture.store.count(1, true, null, null));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(-1L, "visitor", "visitor", List.of("DEMO")),
                                null,
                                List.of()));
        try {
            var service = new AgentService(fixture.store, fixture.clients);
            assertEquals(own, service.detail(own).path("id").asText());
            assertThrows(BusinessException.class, () -> service.detail(other));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void staleFenceCannotCheckpointOrExecuteAfterRecovery() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("fenced", -1);
        var stale = fixture.store.claim();
        fixture.recoverLease();
        var current = fixture.store.claim();
        assertTrue(current.fence() > stale.fence());
        assertThrows(BusinessException.class, () -> fixture.store.assertLease(stale));
        int events = fixture.store.events(id, 0).size();
        assertThrows(
                BusinessException.class,
                () ->
                        fixture.store.checkpoint(
                                stale,
                                "COMPLETED",
                                stale.node(),
                                stale.state(),
                                "STALE_WRITE",
                                AgentJson.object()));
        assertEquals(events, fixture.store.events(id, 0).size());
        fixture.store.assertLease(current);
    }

    @Test
    void pauseAtStepBoundaryAndResumePreserveIntentAndBudget() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("paused", -1);
        var run = fixture.store.claim();
        run.state().put("turns", 4);
        run.state().set("modelIntent", AgentJson.object().put("callId", "durable-original"));
        String deadline = run.state().path("deadline").asText();
        fixture.store.requestPause(id, -1);
        assertThrows(BusinessException.class, () -> fixture.store.assertLease(run));
        fixture.store.checkpoint(
                run, "QUEUED", run.node(), run.state(), "BOUNDARY", AgentJson.object());
        assertEquals("PAUSED", fixture.store.get(id).status());
        fixture.store.resume(id, -1);
        var resumed = fixture.store.get(id);
        assertEquals(4, resumed.state().path("turns").asInt());
        assertEquals(deadline, resumed.state().path("deadline").asText());
        assertEquals(
                "durable-original", resumed.state().path("modelIntent").path("callId").asText());
    }

    @Test
    void approvalRejectsChangedHashDuplicateDecisionAndCancelledRun() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("decision", -1);
        var run = fixture.store.claim();
        ObjectNode call = AgentJson.object().put("id", "call-1").put("name", "APPROVAL");
        run.state().set("toolIntent", call);
        fixture.store.requestApproval(run, call, "WAITING_APPROVAL");
        JsonNode approval = fixture.store.approvals(id).get(0);
        String approvalId = approval.path("id").asText();
        assertThrows(
                BusinessException.class,
                () -> fixture.store.decide(approvalId, 1, "wrong", true, "", -1));
        fixture.store.decide(approvalId, 1, AgentJson.hash(call), true, "", -1);
        assertThrows(
                BusinessException.class,
                () -> fixture.store.decide(approvalId, 1, AgentJson.hash(call), true, "", -1));
        var second = fixture.create("cancel-decision", -1);
        fixture.jdbc.update("UPDATE agent_run SET status='COMPLETED' WHERE id=?", id);
        run = fixture.store.claim();
        run.state().set("toolIntent", call);
        fixture.store.requestApproval(run, call, "WAITING_APPROVAL");
        String cancelledApproval = fixture.store.approvals(second).get(0).path("id").asText();
        fixture.store.cancel(second, -1);
        assertThrows(
                BusinessException.class,
                () ->
                        fixture.store.decide(
                                cancelledApproval, 1, AgentJson.hash(call), true, "", -1));
    }

    @Test
    void pendingTriggersExpireAndKeepFailureEvidence() {
        var fixture = new AgentTestSupport();
        fixture.jdbc.update(
                "INSERT INTO agent_trigger(id,payload_json,last_error,created_at)"
                    + " VALUES('old','{}','owner lease expired',TIMESTAMPADD(MINUTE,-16,NOW(3)))");
        new AgentMessaging(fixture.store, null, fixture.jdbc, null).dispatchTriggers();
        assertEquals(
                "EXPIRED",
                fixture.jdbc.queryForObject("SELECT status FROM agent_trigger", String.class));
        assertEquals(
                "owner lease expired",
                fixture.jdbc.queryForObject("SELECT last_error FROM agent_trigger", String.class));
    }
}
