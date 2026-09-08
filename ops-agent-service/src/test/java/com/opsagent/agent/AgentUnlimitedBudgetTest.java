package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens.Context;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

/**
 * 显式取消完整运行的累计额度，不影响单请求容量、未知结果和审批边界。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentUnlimitedBudgetTest {
    @Test
    void zeroConfigurationFreezesExplicitUnlimitedModeOnlyForNewRunsAndReportsClearLimits() {
        var fixture =
                new AgentTestSupport(
                        new AgentTestSupport.FakeClients() {
                            @Override
                            JsonNode call(
                                    String audience,
                                    String path,
                                    String method,
                                    JsonNode body,
                                    Context actor) {
                                if (path.endsWith("/workspace-context"))
                                    return AgentJson.object()
                                            .put("id", 7)
                                            .put("environment", "ISOLATED")
                                            .put("incidentId", "incident-1")
                                            .put("affectedCiCode", AgentTargets.ORDER)
                                            .put("ownerActorId", -1);
                                if (path.equals("/internal/ai/models")) {
                                    ObjectNode result = AgentJson.object();
                                    result.putArray("models")
                                            .add(
                                                    AgentJson.object()
                                                            .put("provider", "DEEPSEEK")
                                                            .put("toolCalling", true));
                                    return result;
                                }
                                if (path.endsWith("/snapshot"))
                                    return AgentJson.object()
                                            .put("incidentId", "incident-1")
                                            .put("status", "FAULT_ACTIVE");
                                return super.call(audience, path, method, body, actor);
                            }
                        });
        var service = new AgentService(fixture.store, fixture.clients);
        assertEquals(100000, service.limits().get("maxTotalTokens"));
        assertEquals("LIMITED", service.limits().get("tokenBudgetMode"));
        ReflectionTestUtils.setField(service, "runTokenBudget", 0);
        String id =
                service.automatic(
                        AgentJson.object()
                                .put("ownerActorId", -1)
                                .put("environment", "ISOLATED")
                                .put("incidentId", "incident-1")
                                .put("episodeId", "a".repeat(64))
                                .put("ticketId", 7));
        var created = fixture.store.get(id);
        assertEquals("UNLIMITED", created.state().path("tokenBudgetMode").asText());
        assertEquals(0, created.state().path("tokenBudget").asInt(-1));
        assertEquals("UNLIMITED", created.snapshot().path("tokenBudgetMode").asText());
        assertEquals(0, service.limits().get("maxTotalTokens"));
        assertEquals("UNLIMITED", service.limits().get("tokenBudgetMode"));
        assertEquals(12, service.limits().get("maxModelTurns"));
        assertEquals(18, service.limits().get("maxToolCalls"));
        assertEquals(15, service.limits().get("maximumMinutes"));
        ReflectionTestUtils.setField(service, "runTokenBudget", 40000);
        assertTrue(AgentRuntime.unlimited(fixture.store.get(id).state()));
        assertEquals(0, fixture.clients.modelRequests.size());
    }

    @Test
    void capacityResetsPerRequestWhileCumulativeUsageKeepsIncreasingBeyondOneHundredThousand() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("unlimited-over-100k", -1);
        ObjectNode state = enable(fixture, id, 175000);
        String deadline = state.path("deadline").asText();
        fixture.clients.model =
                request -> {
                    assertEquals(100000, request.path("remainingTokens").asInt());
                    assertEquals(4096, request.path("maxOutputTokens").asInt());
                    assertTrue(AgentContext.inputUpperBound(request) <= 32768);
                    ObjectNode response =
                            (ObjectNode)
                                    AgentTestSupport.response("ticket_get", AgentJson.object());
                    return response.put("budgetTokens", 60000).put("totalTokens", 59000);
                };
        for (int i = 0; i < 20 && fixture.clients.modelRequests.size() < 2; i++) fixture.step();
        var current = fixture.store.get(id);
        assertEquals(2, fixture.clients.modelRequests.size());
        assertEquals(295000, current.state().path("tokens").asInt());
        assertEquals(2, current.state().path("turns").asInt());
        assertEquals(deadline, current.state().path("deadline").asText());
        assertEquals(0, current.state().path("tokenBudget").asInt(-1));
        assertEquals(100000, AgentRuntime.modelRequestCapacity(current.state()));
    }

    @Test
    void embeddingReservationsRemainDurableAndDuplicateProtectedWithoutACumulativeCeiling() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("unlimited-embedding", -1);
        enable(fixture, id, 200000);
        var leased = fixture.store.claim();
        assertTrue(AgentRuntime.canSpend(leased.state(), 1800));
        fixture.store.reserveToolAiBudget(
                leased, "knowledge-1", 1800, AgentRuntime.tokenLimit(leased.state()));
        assertEquals(201800, fixture.store.get(id).state().path("tokens").asInt());
        assertEquals(
                1800,
                fixture.store
                        .get(id)
                        .state()
                        .path("toolAiReservations")
                        .path("knowledge-1")
                        .asInt());
        assertThrows(
                RuntimeException.class,
                () -> fixture.store.reserveToolAiBudget(leased, "knowledge-1", 1800, 0));
        assertFalse(AgentRuntime.canSpend(leased.state(), -1));
    }

    @Test
    void legacyBudgetsRemainFiniteAndZeroWithoutExplicitModeCannotBypassTheGate() {
        ObjectNode legacy = AgentJson.object().put("tokens", 40000).put("tokenBudget", 40000);
        assertFalse(AgentRuntime.unlimited(legacy));
        assertEquals(40000, AgentRuntime.tokenLimit(legacy));
        assertEquals(0, AgentRuntime.modelRequestCapacity(legacy));
        assertFalse(AgentRuntime.canSpend(legacy, 1));
        assertEquals(32000, AgentRuntime.tokenLimit(AgentJson.object()));
        legacy.put("tokenBudget", 0);
        assertFalse(AgentRuntime.unlimited(legacy));
        assertFalse(AgentRuntime.canSpend(legacy, 1));
        legacy.put("tokenBudgetMode", "UNLIMITED").put("tokenBudget", 40000);
        assertFalse(AgentRuntime.unlimited(legacy));
        var fixture = new AgentTestSupport();
        fixture.create("legacy-zero-limit", -1);
        var leased = fixture.store.claim();
        assertThrows(
                RuntimeException.class,
                () -> fixture.store.reserveToolAiBudget(leased, "bad-zero", 1800, 0));
    }

    @Test
    void unlimitedStillStopsAtTurnToolAndDeadlineBoundariesAndKeepsUnknownModelFailuresBlocked() {
        for (String boundary :
                java.util.List.of("turns", "toolCount", "deadline", "modelFailure")) {
            var fixture = new AgentTestSupport();
            String id = fixture.create("unlimited-boundary-" + boundary, -1);
            ObjectNode state = enable(fixture, id, 200000);
            if (boundary.equals("turns")) state.put("turns", 12);
            if (boundary.equals("toolCount")) state.put("toolCount", 18);
            if (boundary.equals("deadline"))
                state.put("deadline", Instant.now().minusSeconds(1).toString());
            if (boundary.equals("modelFailure")) {
                state.set("modelFailure", AgentModelFailure.fromMessage("MODEL_NETWORK_ERROR"));
                state.set("modelIntent", AgentJson.object().put("callId", "unknown-paid-call"));
            }
            fixture.jdbc.update(
                    "UPDATE agent_run SET state_json=?,status=? WHERE id=?",
                    state.toString(),
                    boundary.equals("modelFailure") ? "NEEDS_ATTENTION" : "QUEUED",
                    id);
            if (boundary.equals("modelFailure"))
                assertThrows(RuntimeException.class, () -> fixture.store.resume(id, -1));
            else {
                for (int i = 0; i < 3 && fixture.store.get(id).status().equals("QUEUED"); i++)
                    fixture.step();
                assertEquals(
                        boundary.equals("deadline") ? "EXPIRED" : "BUDGET_EXCEEDED",
                        fixture.store.get(id).status());
                assertFalse(
                        fixture.store.get(id).state().path("message").asText().contains("Token预算"));
            }
            assertEquals(0, fixture.clients.modelRequests.size());
            assertEquals(200000, fixture.store.get(id).state().path("tokens").asInt());
        }
    }

    private static ObjectNode enable(AgentTestSupport fixture, String id, int tokens) {
        ObjectNode state = fixture.store.get(id).state();
        state.put("tokenBudgetMode", "UNLIMITED").put("tokenBudget", 0).put("tokens", tokens);
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);
        return state;
    }
}
