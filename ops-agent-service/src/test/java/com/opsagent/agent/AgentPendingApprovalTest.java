package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens.Context;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.web.GlobalExceptionHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 全局待审批范围、有效期与决策状态竞争的数据库回归。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentPendingApprovalTest {
    private final AgentTestSupport fixture = new AgentTestSupport();

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pendingEndpointUsesStandardResponseAndRejectsInvalidLimits() throws Exception {
        pending(-1, "WAITING_APPROVAL");
        pending(-2, "WAITING_APPROVAL");
        authenticate(-1, List.of("DEMO"));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new AgentController(
                                        new AgentService(fixture.store, fixture.clients),
                                        fixture.store,
                                        new AgentWorkspace(fixture.store, fixture.clients)))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        mvc.perform(get("/api/automation/approvals/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].ownerId").value(-1));
        mvc.perform(get("/api/automation/approvals/pending").param("limit", "51"))
                .andExpect(jsonPath("$.code").value(40000));
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/automation/approvals/pending"))
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void ownerSeesBothActionableKindsAndAdminSeesEveryOwner() {
        JsonNode own = pending(-1, "WAITING_APPROVAL");
        pending(-1, "WAITING_INPUT");
        pending(-2, "WAITING_APPROVAL");
        // The queue sorts by expiry, then creation and UUID. Fix the intended first expiry
        // instead of assuming sequential inserts cannot share a millisecond timestamp.
        fixture.jdbc.update(
                "UPDATE agent_approval SET expires_at=TIMESTAMPADD(SECOND,120,NOW(3)) WHERE id=?",
                own.path("id").asText());
        authenticate(-1, List.of("DEMO"));
        var service = new AgentService(fixture.store, fixture.clients);
        JsonNode result = AgentJson.tree(service.pendingApprovals(1));
        assertEquals(2, result.path("total").asInt());
        assertEquals(1, result.path("items").size());
        JsonNode item = result.path("items").get(0);
        assertEquals(own.path("id"), item.path("id"));
        assertEquals(own.path("run_id"), item.path("run_id"));
        assertEquals(own.path("args_hash"), item.path("args_hash"));
        assertEquals(1, item.path("revision").asInt());
        assertEquals("PENDING", item.path("status").asText());
        assertEquals("WAITING_APPROVAL", item.path("runStatus").asText());
        assertEquals(-1, item.path("ownerId").asLong());
        assertEquals(7, item.path("ticketId").asLong());
        assertEquals("incident-1", item.path("incidentId").asText());
        assertEquals("diagnose", item.path("nodeId").asText());
        assertEquals("AI 诊断与处置", item.path("nodeLabel").asText());
        assertFalse(item.path("pauseRequested").asBoolean());
        assertTrue(Instant.parse(item.path("expires_at").asText()).isAfter(Instant.now()));
        assertTrue(Instant.parse(item.path("runDeadline").asText()).isAfter(Instant.now()));
        assertEquals(own.path("payload"), item.path("payload"));
        assertFalse(item.has("state"));
        assertFalse(item.has("snapshot"));
        assertFalse(item.has("actor"));
        authenticate(1, List.of("ADMIN"));
        assertEquals(3, AgentJson.tree(service.pendingApprovals(20)).path("total").asInt());
    }

    @Test
    void limitCutsOnlyItemsAndRetainsExactTotalAtRuntimeCapacity() {
        for (int i = 0; i < 8; i++) pending(-1, "WAITING_APPROVAL");
        JsonNode result = AgentJson.tree(fixture.store.pendingApprovals(2, -1, false));
        assertEquals(8, result.path("total").asInt());
        assertEquals(2, result.path("items").size());
        assertEquals(result, AgentJson.tree(fixture.store.pendingApprovals(2, -1, false)));
        assertEquals(
                8,
                AgentJson.tree(fixture.store.pendingApprovals(50, -1, false)).path("items").size());
        assertThrows(BusinessException.class, () -> fixture.store.pendingApprovals(0, -1, false));
        assertThrows(BusinessException.class, () -> fixture.store.pendingApprovals(51, -1, false));
    }

    @Test
    void expiredAndHistoricalRowsDoNotConsumeLimitOrCount() {
        JsonNode expired = pending(-1, "WAITING_APPROVAL");
        fixture.jdbc.update(
                "UPDATE agent_approval SET expires_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",
                expired.path("id").asText());
        JsonNode historical = pending(-1, "WAITING_APPROVAL");
        var oldRun = fixture.store.get(historical.path("run_id").asText());
        ((ObjectNode) oldRun.state().path("toolIntent")).put("id", "another-current-call");
        fixture.jdbc.update(
                "UPDATE agent_run SET state_json=? WHERE id=?",
                oldRun.state().toString(),
                oldRun.id());
        JsonNode valid = pending(-1, "WAITING_INPUT");
        JsonNode result = AgentJson.tree(fixture.store.pendingApprovals(1, -1, false));
        assertEquals(1, result.path("total").asInt());
        assertEquals(1, result.path("items").size());
        assertEquals(valid.path("id"), result.path("items").get(0).path("id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED", "EXPIRED"})
    void excludesNonPendingApprovals(String status) {
        JsonNode approval = pending(-1, "WAITING_APPROVAL");
        fixture.jdbc.update(
                "UPDATE agent_approval SET status=? WHERE id=?",
                status,
                approval.path("id").asText());
        assertNoPending();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "PAUSED", "CANCELLED", "FAILED", "COMPLETED"})
    void excludesRunsThatAreNotWaiting(String status) {
        JsonNode approval = pending(-1, "WAITING_APPROVAL");
        fixture.jdbc.update(
                "UPDATE agent_run SET status=? WHERE id=?",
                status,
                approval.path("run_id").asText());
        assertNoPending();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause_requested", "cancel_requested"})
    void excludesRequestedPauseAndCancelBeforeRuntimeChangesStatus(String flag) {
        JsonNode approval = pending(-1, "WAITING_INPUT");
        fixture.jdbc.update(
                "UPDATE agent_run SET " + flag + "=TRUE WHERE id=?",
                approval.path("run_id").asText());
        assertNoPending();
        assertDecisionRejectedWithoutEffects(approval, 1, approval.path("args_hash").asText());
    }

    @Test
    void excludesExpiredApprovalIndependentlyOfRunDeadline() {
        JsonNode approval = pending(-1, "WAITING_APPROVAL");
        fixture.jdbc.update(
                "UPDATE agent_approval SET expires_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?",
                approval.path("id").asText());
        assertNoPending();
        assertDecisionRejectedWithoutEffects(approval, 1, approval.path("args_hash").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2000-01-01T00:00:00Z", "", "malformed"})
    void excludesExpiredOrInvalidRunDeadlineIndependentlyOfApprovalExpiry(String deadline) {
        JsonNode approval = pending(-1, "WAITING_APPROVAL");
        var run = fixture.store.get(approval.path("run_id").asText());
        run.state().put("deadline", deadline);
        fixture.jdbc.update(
                "UPDATE agent_run SET state_json=? WHERE id=?", run.state().toString(), run.id());
        assertNoPending();
        assertDecisionRejectedWithoutEffects(approval, 1, approval.path("args_hash").asText());
    }

    @Test
    void ignoresHistoricalApprovalForAnotherToolIntent() {
        JsonNode approval = pending(-1, "WAITING_APPROVAL");
        var run = fixture.store.get(approval.path("run_id").asText());
        ((ObjectNode) run.state().path("toolIntent")).put("id", "new-current-call");
        fixture.jdbc.update(
                "UPDATE agent_run SET state_json=? WHERE id=?", run.state().toString(), run.id());
        assertNoPending();
        assertDecisionRejectedWithoutEffects(approval, 1, approval.path("args_hash").asText());
    }

    @Test
    void changedCurrentArgumentsInvalidateApprovalEvenWhenCallIdMatches() {
        JsonNode approval = pending(-1, "WAITING_APPROVAL");
        var run = fixture.store.get(approval.path("run_id").asText());
        ((ObjectNode) run.state().path("toolIntent")).put("prompt", "已变化的动作参数");
        fixture.jdbc.update(
                "UPDATE agent_run SET state_json=? WHERE id=?", run.state().toString(), run.id());
        assertNoPending();
        assertDecisionRejectedWithoutEffects(approval, 1, approval.path("args_hash").asText());
    }

    @Test
    void exactRevisionAndHashRemainRequiredAndSuccessDisappearsFromPending() {
        JsonNode approval = pending(-1, "WAITING_INPUT");
        assertDecisionRejectedWithoutEffects(approval, 2, approval.path("args_hash").asText());
        assertDecisionRejectedWithoutEffects(approval, 1, "changed-hash");
        authenticate(-1, List.of("DEMO"));
        var service = new AgentService(fixture.store, fixture.clients);
        service.decide(
                approval.path("id").asText(),
                1,
                approval.path("args_hash").asText(),
                true,
                "已核对当前参数");
        assertNoPending();
        JsonNode decided = fixture.store.approval(approval.path("id").asText());
        assertEquals("APPROVED", decided.path("status").asText());
        assertEquals(2, decided.path("revision").asInt());
        assertEquals("QUEUED", fixture.store.get(approval.path("run_id").asText()).status());
        assertThrows(
                BusinessException.class,
                () ->
                        service.decide(
                                approval.path("id").asText(),
                                1,
                                approval.path("args_hash").asText(),
                                true,
                                ""));
    }

    @Test
    void serviceUsesRefreshedIdentityAndRoleInsteadOfCachedAdminClaim() {
        pending(-1, "WAITING_APPROVAL");
        JsonNode foreign = pending(-2, "WAITING_APPROVAL");
        authenticate(-1, List.of("ADMIN", "DEMO"));
        var clients =
                new AgentTestSupport.FakeClients() {
                    int refreshes;

                    @Override
                    Context current(OpsPrincipal principal, String run) {
                        refreshes++;
                        return new Context(
                                principal.userId(),
                                principal.username(),
                                List.of("DEMO"),
                                run,
                                "ops-demo-order-service",
                                Instant.now().plusSeconds(60));
                    }
                };
        var service = new AgentService(fixture.store, clients);
        assertEquals(1, AgentJson.tree(service.pendingApprovals(20)).path("total").asInt());
        assertEquals(1, clients.refreshes);
        assertThrows(
                BusinessException.class,
                () ->
                        service.decide(
                                foreign.path("id").asText(),
                                1,
                                foreign.path("args_hash").asText(),
                                true,
                                ""));
        assertEquals(2, clients.refreshes);
        assertEquals(
                "PENDING",
                fixture.store.approval(foreign.path("id").asText()).path("status").asText());
    }

    @Test
    void revokedOrMissingIdentityCannotListPendingApprovals() {
        pending(-1, "WAITING_APPROVAL");
        var clients =
                new AgentTestSupport.FakeClients() {
                    @Override
                    Context current(OpsPrincipal principal, String run) {
                        throw AgentClients.denied();
                    }
                };
        var service = new AgentService(fixture.store, clients);
        assertThrows(BusinessException.class, () -> service.pendingApprovals(20));
        authenticate(-1, List.of("DEMO"));
        assertThrows(BusinessException.class, () -> service.pendingApprovals(20));
        assertThrows(BusinessException.class, () -> service.pendingApprovals(0));
        assertThrows(BusinessException.class, () -> service.pendingApprovals(51));
    }

    @Test
    void refreshedAdminCanDecideAnotherOwnersApproval() {
        JsonNode approval = pending(-2, "WAITING_APPROVAL");
        authenticate(1, List.of("ADMIN"));
        var service = new AgentService(fixture.store, fixture.clients);
        service.decide(
                approval.path("id").asText(),
                1,
                approval.path("args_hash").asText(),
                false,
                "当前方案需要重新评估");
        JsonNode decided = fixture.store.approval(approval.path("id").asText());
        assertEquals("REJECTED", decided.path("status").asText());
        assertEquals(1, decided.path("decided_by").asLong());
        assertEquals(0, AgentJson.tree(service.pendingApprovals(20)).path("total").asInt());
    }

    private JsonNode pending(long owner, String status) {
        String id = fixture.create(UUID.randomUUID().toString(), owner);
        fixture.jdbc.update("UPDATE agent_run SET node_id='diagnose' WHERE id=?", id);
        var run = fixture.store.claim();
        assertEquals(id, run.id());
        ObjectNode call =
                AgentJson.object()
                        .put("id", UUID.randomUUID().toString())
                        .put("name", status.equals("WAITING_INPUT") ? "HUMAN_INPUT" : "APPROVAL");
        call.set("arguments", AgentJson.object().put("prompt", "请核对并确认本次隔离处置"));
        run.state().set("toolIntent", call);
        fixture.store.requestApproval(run, call, status);
        return fixture.store.approvals(id).get(0);
    }

    private void assertNoPending() {
        JsonNode result = AgentJson.tree(fixture.store.pendingApprovals(20, -1, false));
        assertEquals(0, result.path("total").asInt());
        assertTrue(result.path("items").isEmpty());
    }

    private void assertDecisionRejectedWithoutEffects(
            JsonNode approval, int revision, String hash) {
        String run = approval.path("run_id").asText();
        int events = fixture.store.events(run, 0).size();
        int outbox = fixture.store.outbox().size();
        String status = fixture.store.get(run).status();
        assertThrows(
                BusinessException.class,
                () ->
                        fixture.store.decide(
                                approval.path("id").asText(), revision, hash, true, "", -1));
        assertEquals(
                "PENDING",
                fixture.store.approval(approval.path("id").asText()).path("status").asText());
        assertEquals(events, fixture.store.events(run, 0).size());
        assertEquals(outbox, fixture.store.outbox().size());
        assertEquals(status, fixture.store.get(run).status());
    }

    private static void authenticate(long owner, List<String> roles) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(owner, "visitor", "visitor", roles),
                                null,
                                List.of()));
    }
}
