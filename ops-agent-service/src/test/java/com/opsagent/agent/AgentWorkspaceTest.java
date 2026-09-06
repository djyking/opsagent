package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens.Context;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 工作区跨源权限、事件归属和时效性验证，不把流程完成当作恢复证据。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentWorkspaceTest {
    private final AgentTestSupport fixture = new AgentTestSupport();
    private final WorkspaceClients clients = new WorkspaceClients();

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deniedTicketNeverReadsRunsApprovalsOrPlatform() {
        authenticate(-1, List.of("DEMO"));
        clients.denyTicket = true;
        AgentStore store = mock(AgentStore.class);
        assertThrows(BusinessException.class, () -> new AgentWorkspace(store, clients).view(7));
        verifyNoInteractions(store);
        assertEquals(List.of("/internal/agent/tickets/7/workspace-context"), clients.calls);
    }

    @Test
    void publicTicketNeverAmplifiesPrivateRunApprovalOrIncidentVisibility() {
        String privateRun = fixture.create("private", -2);
        var run = fixture.store.claim();
        ObjectNode call = AgentJson.object().put("id", "private-call").put("name", "APPROVAL");
        run.state().set("toolIntent", call);
        fixture.store.requestApproval(run, call, "WAITING_APPROVAL");
        String privateApproval = fixture.store.approvals(privateRun).get(0).path("id").asText();
        clients.ticket.put("ownerActorId", -2).put("publicDemo", true);
        authenticate(-1, List.of("DEMO"));

        JsonNode result = new AgentWorkspace(fixture.store, clients).view(7);

        assertEquals("RESTRICTED", result.path("verification").path("status").asText());
        assertEquals(0, result.path("runTotal").asInt());
        assertTrue(result.path("runs").isEmpty());
        assertTrue(result.path("pendingApprovalIds").isEmpty());
        assertFalse(result.toString().contains(privateRun));
        assertFalse(result.toString().contains(privateApproval));
        assertFalse(result.path("actions").path("canDiagnose").asBoolean());
        assertEquals(List.of("/internal/agent/tickets/7/workspace-context"), clients.calls);
    }

    @Test
    void adminGetsBoundedRunListExactTotalAndStructuredModelHypotheses() {
        for (int index = 0; index < 10; index++) {
            String id = fixture.create("history-" + index, index % 2 == 0 ? -1 : -2);
            var state = fixture.store.get(id).state();
            state.set(
                    "diagnosis",
                    AgentJson.object()
                            .put("summary", "连接端口异常需核验")
                            .put("knownFacts", "HTTP503\n连接失败")
                            .put("candidateCauses", "最近配置发布可能改变端口")
                            .put("evidenceGaps", "尚缺少配置发布前的业务探针")
                            .put("recordedAt", Instant.now().toString()));
            fixture.jdbc.update(
                    "UPDATE agent_run SET status='COMPLETED',state_json=? WHERE id=?",
                    state.toString(),
                    id);
        }
        authenticate(1, List.of("ADMIN"));

        JsonNode result = new AgentWorkspace(fixture.store, clients).view(7);

        assertEquals(10, result.path("runTotal").asInt());
        assertEquals(6, result.path("runs").size());
        assertEquals("FAULT_ACTIVE", result.path("verification").path("status").asText());
        assertNotEquals("RECOVERED", result.path("stage").path("code").asText());
        assertEquals("MODEL", result.path("hypotheses").path(0).path("source").asText());
        assertEquals(2, result.path("diagnosis").path("knownFacts").size());
        assertTrue(result.path("actions").path("canDiagnose").asBoolean());
        assertFalse(result.path("runs").path(0).has("snapshot"));
        assertFalse(result.path("runs").path(0).has("state"));
        assertEquals(12, result.path("changes").size());
        assertFalse(result.path("changes").toString().contains("PRIVATE_CONFIGURATION_SECRET"));
    }

    @Test
    void snapshotOfAnotherIncidentNeverContributesRecoveryEvidence() {
        ObjectNode evidence = evidence();
        ((ObjectNode) evidence.path("snapshot")).put("incidentId", "different-event");
        ((ObjectNode) evidence.path("snapshot").path("business"))
                .put("httpStatus", 200)
                .put("consecutiveSuccesses", 3);
        JsonNode result =
                AgentWorkspace.determine(
                        "incident-1",
                        evidence,
                        AgentJson.object().put("currentStatus", "resolved"));
        assertEquals("INCIDENT_CHANGED", result.path("status").asText());
        assertFalse(result.path("incidentMatched").asBoolean());
        assertFalse(result.path("businessHealthy").asBoolean());
        assertFalse(result.path("agentAttributed").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(ints = {-60, 60})
    void staleOrFutureBusinessProbeNeverVerifiesRecovery(int seconds) {
        ObjectNode evidence = evidence();
        ((ObjectNode) evidence.path("snapshot").path("business"))
                .put("httpStatus", 200)
                .put("consecutiveSuccesses", 3)
                .put("observedAt", Instant.now().plusSeconds(seconds).toString());
        JsonNode result =
                AgentWorkspace.determine(
                        "incident-1",
                        evidence,
                        AgentJson.object().put("currentStatus", "resolved"));
        assertEquals("STALE", result.path("status").asText());
        assertFalse(result.path("agentAttributed").asBoolean());
    }

    @Test
    void historicalRecoveryIsDatedHistoryNeverCurrentHealthyOrAgentSuccess() {
        ObjectNode evidence = evidence().put("current", false);
        evidence.set(
                "incident",
                AgentJson.object()
                        .put("status", "RECOVERED")
                        .put("recoveredAt", "2026-09-05T01:00:00Z")
                        .put("lastObservedAt", "2026-09-05T01:00:00Z")
                        .put("recoverySource", "AGENT_TOOL"));
        JsonNode result = AgentWorkspace.determine("incident-1", evidence, null);
        assertEquals("HISTORICAL_RECOVERY", result.path("status").asText());
        assertEquals("HISTORICAL", result.path("scope").asText());
        assertEquals("2026-09-05T01:00:00Z", result.path("observedAt").asText());
        assertFalse(result.path("businessHealthy").asBoolean());
        assertFalse(result.path("agentAttributed").asBoolean());
    }

    @Test
    void unavailableLiveTargetIsNotReportedAsAChangedIncident() {
        ObjectNode evidence =
                evidence().put("current", false).put("captureStatus", "LIVE_TARGET_UNAVAILABLE");
        evidence.set(
                "incident",
                AgentJson.object()
                        .put("status", "FAULT_ACTIVE")
                        .put("lastObservedAt", "2026-09-05T01:00:00Z"));
        JsonNode result = AgentWorkspace.determine("incident-1", evidence, null);
        assertEquals("UNAVAILABLE", result.path("status").asText());
        assertEquals("HISTORICAL", result.path("scope").asText());
        assertFalse(result.path("businessHealthy").asBoolean());
    }

    @Test
    void currentRecoveryNeedsThreeSuccessesResolvedEpisodeAndAttributionSource() {
        ObjectNode evidence = evidence();
        ObjectNode snapshot = (ObjectNode) evidence.path("snapshot");
        ((ObjectNode) snapshot.path("business"))
                .put("httpStatus", 200)
                .put("consecutiveSuccesses", 3);
        JsonNode result =
                AgentWorkspace.determine(
                        "incident-1", evidence, AgentJson.object().put("currentStatus", "firing"));
        assertEquals("EPISODE_PENDING", result.path("status").asText());
        snapshot.put("recoverySource", "TTL_GUARD");
        result =
                AgentWorkspace.determine(
                        "incident-1",
                        evidence,
                        AgentJson.object().put("currentStatus", "resolved"));
        assertEquals("RECOVERED", result.path("status").asText());
        assertFalse(result.path("agentAttributed").asBoolean());
        snapshot.put("recoverySource", "AGENT_TOOL");
        result =
                AgentWorkspace.determine(
                        "incident-1",
                        evidence,
                        AgentJson.object().put("currentStatus", "resolved"));
        assertTrue(result.path("agentAttributed").asBoolean());
    }

    @Test
    void aggregateSummaryCountsEntireOwnerScopeInsteadOfTheFirstPage() {
        for (int index = 0; index < 17; index++) {
            String id = fixture.create("count-" + index, index < 11 ? -1 : -2);
            fixture.jdbc.update("UPDATE agent_run SET status='COMPLETED' WHERE id=?", id);
        }
        fixture.create("current", -1);
        authenticate(-1, List.of("DEMO"));
        JsonNode result =
                AgentJson.tree(new AgentService(fixture.store, fixture.clients).summary());
        assertEquals("OWNER", result.path("scope").asText());
        assertEquals(12, result.path("totalRuns").asInt());
        assertEquals(11, result.path("statusCounts").path("COMPLETED").asInt());
        assertEquals(1, result.path("activeRuns").asInt());
        authenticate(1, List.of("ADMIN"));
        assertEquals(
                18,
                AgentJson.tree(new AgentService(fixture.store, fixture.clients).summary())
                        .path("totalRuns")
                        .asInt());
    }

    @Test
    void manualRunBindsAuthorizedTicketTargetAndFreezesOnlyItsOwnRepairTools() {
        authenticate(-1, List.of("DEMO"));
        clients.ticket.put("affectedCiCode", AgentTargets.NOTIFICATION);
        String id =
                new AgentService(fixture.store, clients)
                        .manual(7, "isolated-recovery", "DEEPSEEK", "notification-create-1");
        var run = fixture.store.get(id);
        assertEquals(AgentTargets.NOTIFICATION, run.state().path("targetCode").asText());
        assertEquals(AgentTargets.NOTIFICATION, clients.modelTarget);
        assertEquals(AgentTargets.NOTIFICATION, clients.fromState(run.state()).targetCode());
        assertTrue(run.snapshot().path("tools").toString().contains("demo_queue_restore"));
        assertFalse(run.snapshot().path("tools").toString().contains("demo_config_restore"));
        assertEquals("/internal/agent/tickets/7/workspace-context", clients.calls.get(0));
    }

    private static ObjectNode evidence() {
        ObjectNode result =
                AgentJson.object()
                        .put("incidentId", "incident-1")
                        .put("targetCode", AgentTargets.ORDER)
                        .put("current", true);
        ObjectNode snapshot =
                AgentJson.object()
                        .put("incidentId", "incident-1")
                        .put("status", "FAULT_ACTIVE")
                        .put("appliedRevision", "a".repeat(64))
                        .put("recoverySource", "NONE");
        snapshot.set(
                "business",
                AgentJson.object()
                        .put("httpStatus", 503)
                        .put("consecutiveSuccesses", 0)
                        .put("reasonCode", "CONNECTION_REFUSED")
                        .put("observedAt", Instant.now().toString()));
        result.set("snapshot", snapshot);
        var changes = result.putArray("changes");
        for (int i = 0; i < 20; i++) {
            ObjectNode change =
                    AgentJson.object().put("id", "change-" + i).put("kind", "CONFIGURATION");
            change.set(
                    "after",
                    AgentJson.object()
                            .put("redisPort", 6380)
                            .put("password", "PRIVATE_CONFIGURATION_SECRET"));
            changes.add(change);
        }
        return result;
    }

    private static void authenticate(long owner, List<String> roles) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(
                                        owner, "owner", UUID.randomUUID().toString(), roles),
                                null,
                                List.of()));
    }

    /**
     * @author heyu
     */
    private static final class WorkspaceClients extends AgentTestSupport.FakeClients {
        final List<String> calls = new ArrayList<>();
        final ObjectNode ticket =
                AgentJson.object()
                        .put("id", 7)
                        .put("status", "PROCESSING")
                        .put("environment", "ISOLATED")
                        .put("ownerActorId", -1)
                        .put("incidentId", "incident-1")
                        .put("episodeId", "a".repeat(64))
                        .put("affectedCiCode", AgentTargets.ORDER);
        boolean denyTicket;
        String modelTarget;

        @Override
        JsonNode call(String audience, String path, String method, JsonNode body, Context actor) {
            calls.add(path);
            if (path.endsWith("/workspace-context")) {
                if (denyTicket) throw AgentClients.denied();
                return ticket;
            }
            if (path.endsWith("/evidence")) return evidence();
            if (path.equals("/internal/ai/models")) {
                modelTarget = actor.targetCode();
                ObjectNode result = AgentJson.object();
                result.putArray("models")
                        .add(
                                AgentJson.object()
                                        .put("provider", "DEEPSEEK")
                                        .put("model", "verified-test-model")
                                        .put("toolCalling", true));
                return result;
            }
            if (path.contains("/alerts/")) return AgentJson.object().put("currentStatus", "firing");
            throw new AssertionError("Unexpected outbound request");
        }
    }
}
