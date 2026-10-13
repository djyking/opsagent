package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens.Context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistent AgentRun and exact approval tests for the configuration bridge.
 *
 * @author heyu
 * @since 2026/9/3
 */
class ConfigurationRunTest {
    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "UNCONFIRMED", "CONFLICT", "UNKNOWN"})
    void unconfirmedApplicationPreservesIntentAndResumesSameApprovedProposal(String status) {
        var clients = new ConfigurationClients();
        clients.resultStatus = status;
        var fixture = new AgentTestSupport(clients);
        String id = create(fixture, "configuration-test-" + status);
        fixture.finish(id);
        assertEquals("WAITING_APPROVAL", fixture.store.get(id).status());
        assertEquals(0, clients.actions);
        var approval = fixture.store.approvals(id).get(0);
        fixture.store.decide(
                approval.path("id").asText(),
                1,
                approval.path("args_hash").asText(),
                true,
                "approved exact patch",
                1);
        fixture.finish(id);
        var run = fixture.store.get(id);
        assertEquals("NEEDS_ATTENTION", run.status());
        assertEquals(
                status,
                run.state()
                        .path("configurationVerification")
                        .path("operation")
                        .path("status")
                        .asText());
        var intent = run.state().path("toolIntent").deepCopy();
        assertFalse(intent.isMissingNode());
        assertEquals(0, run.state().path("turns").asInt());
        assertEquals(1, clients.actions);
        clients.resultStatus = "APPLIED";
        fixture.store.resume(id, 1);
        fixture.finish(id);
        assertEquals("COMPLETED", fixture.store.get(id).status());
        assertEquals(1, fixture.store.approvals(id).size());
        assertEquals(2, clients.actions);
        assertEquals(clients.writes.get(0), clients.writes.get(1));
        assertTrue(clients.modelRequests.isEmpty());
    }

    @Test
    void proposalMutationAfterApprovalCannotReachConfigurationUpstream() {
        var clients = new ConfigurationClients();
        var fixture = new AgentTestSupport(clients);
        String id = create(fixture, "configuration-drift");
        fixture.finish(id);
        var approval = fixture.store.approvals(id).get(0);
        fixture.store.decide(
                approval.path("id").asText(),
                1,
                approval.path("args_hash").asText(),
                true,
                "approved exact patch",
                1);
        var state = fixture.store.get(id).state();
        ((ObjectNode) state.path("toolIntent").path("arguments"))
                .put("immutableDigest", "b".repeat(64));
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);
        fixture.finish(id);
        assertEquals("NEEDS_ATTENTION", fixture.store.get(id).status());
        assertEquals(0, clients.actions);
    }

    @Test
    void diagnosticRegistryCannotExposeConfigurationExecutionOrOverrideEvidenceScope() {
        assertThrows(
                RuntimeException.class,
                () ->
                        AgentTools.validate(
                                "ticket_add_analysis",
                                AgentJson.object()
                                        .put("summary", "s")
                                        .put("evidence", "e")
                                        .put("recommendation", "r")
                                        .put("conclusionLevel", "HUMAN_CONFIRMED")));
        assertFalse(
                AgentTools.schemas(AgentTargets.ORDER).toString().contains("config_change_apply"));
        assertTrue(
                AgentTools.schemas(AgentTargets.ORDER)
                        .toString()
                        .contains("observability_evidence"));
        assertThrows(
                RuntimeException.class,
                () ->
                        AgentTools.validate(
                                "observability_evidence",
                                AgentJson.object().put("service", "another-service")));
        String text =
                AgentContext.project(
                        AgentJson.object()
                                .put("id", "read:1")
                                .put("name", "observability_evidence"),
                        AgentJson.read(
                                """
{"evidenceBundleId":"fixed","quality":"PARTIAL","gaps":["trace missing"],
 "entries":[{"id":"E-1","source":"PROBE","observedAt":"2026-09-06T00:00:00Z",
 "quality":"FRESH","summary":"probe 200","data":{"secret":"do-not-forward"}}]}
"""));
        assertTrue(text.contains("E-1"));
        assertTrue(text.contains("trace missing"));
        assertFalse(text.contains("do-not-forward"));
    }

    private String create(AgentTestSupport fixture, String trigger) {
        String id = UUID.randomUUID().toString();
        String proposal = UUID.randomUUID().toString();
        ObjectNode snapshot = AgentJson.object();
        snapshot.set("graph", WorkflowGraph.configurationChange());
        snapshot.set("tools", AgentTools.schemas());
        snapshot.set(
                "configurationProposal",
                AgentJson.object()
                        .put("proposalId", proposal)
                        .put("immutableDigest", "a".repeat(64)));
        ObjectNode state =
                AgentJson.object()
                        .put("runId", id)
                        .put("ticketId", 0)
                        .put("incidentId", "")
                        .put("targetCode", AgentTargets.ORDER)
                        .put("deadline", Instant.now().plusSeconds(600).toString());
        state.set(
                "actor",
                fixture.clients.actorJson(
                        new Context(
                                1,
                                "admin",
                                List.of("ADMIN"),
                                id,
                                AgentTargets.ORDER,
                                Instant.now().plusSeconds(600))));
        state.withObject("/outputs").set("proposal", snapshot.path("configurationProposal"));
        return fixture.store.create("configuration-change", trigger, 1, snapshot, state);
    }

    static class ConfigurationClients extends AgentTestSupport.FakeClients {
        String resultStatus = "APPLIED";

        @Override
        JsonNode call(String audience, String path, String method, JsonNode body, Context actor) {
            if (path.startsWith("/internal/platform/configuration/proposals/")
                    && path.endsWith("/apply")) {
                actions++;
                writes.add(body.deepCopy());
                assertEquals("platform", audience);
                assertEquals("POST", method);
                ObjectNode result = AgentJson.object();
                result.set("operation", AgentJson.object().put("status", resultStatus));
                return result;
            }
            return super.call(audience, path, method, body, actor);
        }
    }
}
