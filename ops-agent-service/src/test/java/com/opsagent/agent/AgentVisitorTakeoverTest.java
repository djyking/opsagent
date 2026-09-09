package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens.Context;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @author heyu
 */
class AgentVisitorTakeoverTest {
    private final AccessClients clients = new AccessClients();
    private final AgentTestSupport fixture = new AgentTestSupport(clients);
    private final AgentService service = new AgentService(fixture.store, clients);

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void visitorApprovesOnlyOwnExactCurrentRepairAndMustGiveReason() {
        String id = waiting();
        JsonNode approval = fixture.store.approvals(id).get(0);
        actor(-2, "DEMO");
        assertCode("RUN_NOT_OWNED", () -> decide(approval, "已核对本次修复"));
        actor(-1, "DEMO");
        assertThrows(com.opsagent.common.core.BusinessException.class, () -> decide(approval, " "));
        clients.target.put("expectedRevision", "b".repeat(64));
        assertCode("TARGET_REVISION_CHANGED", () -> decide(approval, "已核对本次修复"));
        assertEquals(
                "PENDING",
                fixture.store.approval(approval.path("id").asText()).path("status").asText());
        clients.target.put("expectedRevision", "a".repeat(64));
        decide(approval, "同意本次隔离演练的精确配置恢复");
        assertEquals(
                -1,
                fixture.store.approval(approval.path("id").asText()).path("decided_by").asLong());
        assertEquals("QUEUED", fixture.store.get(id).status());
    }

    @Test
    void administratorApprovalCannotReplaceRevokedVisitorIdentity() {
        String id = waiting();
        actor(1, "ADMIN");
        clients.inactive = "VISITOR_REVOKED";
        assertCode("VISITOR_REVOKED", () -> decide(fixture.store.approvals(id).get(0), "管理员同意"));
        assertEquals("WAITING_APPROVAL", fixture.store.get(id).status());
        assertEquals(0, clients.actions);
        assertTrue(service.detail(id).path("authorization").path("canTakeover").asBoolean());
        assertFalse(service.detail(id).path("authorization").path("canOperate").asBoolean());
        JsonNode pending = AgentJson.tree(service.pendingApprovals(20)).path("items").get(0);
        assertFalse(pending.path("canOperate").asBoolean());
        assertEquals("VISITOR_REVOKED", pending.path("authorization").path("reasonCode").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VISITOR_REVOKED", "VISITOR_LEASE_EXPIRED"})
    void runtimePersistsExactIdentityFailureAndNeverResumesOldRun(String reason) {
        String id = fixture.create("failed-identity", -1);
        clients.inactive = reason;
        fixture.step();
        var old = fixture.store.get(id);
        assertEquals("NEEDS_ATTENTION", old.status());
        assertEquals(reason, old.state().path("authorizationFailure").path("reasonCode").asText());
        assertTrue(old.state().path("authorizationFailure").path("requiresNewRun").asBoolean());
        assertCode("NEW_RUN_REQUIRED", () -> fixture.store.resume(id, 1));
        assertTrue(clients.modelRequests.isEmpty());
        assertEquals(0, clients.actions);
    }

    @Test
    void takeoverCreatesAuditedAdminRunWithoutReusingOldApprovalOrRevivingFailedHistory() {
        String oldId = waiting();
        JsonNode originalSnapshot = fixture.store.get(oldId).snapshot().deepCopy();
        clients.inactive = "VISITOR_REVOKED";
        actor(1, "ADMIN");
        var preview = service.takeoverPreview(oldId);
        assertEquals("a".repeat(64), preview.path("expectedRevision").asText());
        String newId =
                service.takeover(
                        oldId,
                        "takeover-request-1",
                        "原访客结束体验，接管当前隔离故障",
                        "incident-1",
                        "a".repeat(64));
        assertNotEquals(oldId, newId);
        assertEquals("NEEDS_ATTENTION", fixture.store.get(oldId).status());
        assertEquals(originalSnapshot, fixture.store.get(oldId).snapshot());
        assertEquals("EXPIRED", fixture.store.approvals(oldId).get(0).path("status").asText());
        var created = fixture.store.get(newId);
        assertEquals(1, created.owner());
        assertEquals(1, created.state().path("actor").path("userId").asLong());
        assertFalse(created.state().has("toolIntent"));
        assertTrue(fixture.store.approvals(newId).isEmpty());
        assertEquals(oldId, created.snapshot().path("takeover").path("sourceRunId").asText());
        assertTrue(
                fixture.store.events(newId, 0).stream()
                        .anyMatch(event -> event.toString().contains("ADMIN_TAKEOVER_CREATED")));
        assertEquals(
                newId,
                service.takeover(
                        oldId,
                        "takeover-request-1",
                        "原访客结束体验，接管当前隔离故障",
                        "incident-1",
                        "a".repeat(64)));
        assertCode(
                "TAKEOVER_ALREADY_CREATED",
                () ->
                        service.takeover(
                                oldId, "takeover-request-2", "另一请求", "incident-1", "a".repeat(64)));
        assertEquals(2L, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM agent_run", Long.class));
        assertEquals(0, clients.actions);
    }

    @Test
    void failedOldRunRemainsByteForByteUnchangedAfterTakeover() {
        String oldId = fixture.create("already-failed", -1);
        clients.inactive = "VISITOR_REVOKED";
        fixture.step();
        var old = fixture.store.get(oldId);
        actor(1, "ADMIN");
        service.takeover(oldId, "takeover-request-3", "接管故障", "incident-1", "a".repeat(64));
        assertEquals(old.state(), fixture.store.get(oldId).state());
        assertEquals(old.status(), fixture.store.get(oldId).status());
    }

    @Test
    void previewAndCommitRevalidateIdentityTargetAndUncertainWrites() {
        String id = waiting();
        actor(1, "ADMIN");
        assertCode("TAKEOVER_NOT_REQUIRED", () -> service.takeoverPreview(id));
        clients.inactive = "VISITOR_REVOKED";
        clients.target.put("incidentId", "another-incident");
        assertCode("INCIDENT_CHANGED", () -> service.takeoverPreview(id));
        clients.target.put("incidentId", "incident-1");
        clients.target.put("status", "BASELINE");
        assertCode("TARGET_ALREADY_RECOVERED", () -> service.takeoverPreview(id));
        clients.target.put("status", "FAULT_ACTIVE");
        assertCode(
                "TARGET_REVISION_CHANGED",
                () ->
                        service.takeover(
                                id, "takeover-request-4", "接管故障", "incident-1", "b".repeat(64)));
        ObjectNode state = fixture.store.get(id).state();
        ((ObjectNode) state.path("toolIntent"))
                .set("preparedRequest", AgentJson.object().put("idempotencyKey", "unknown-write"));
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);
        assertCode("RUN_BUSY", () -> service.takeoverPreview(id));
        assertEquals(1L, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM agent_run", Long.class));
    }

    @Test
    void identityServiceOutageNeverBecomesTakeoverPermission() {
        String id = waiting();
        actor(1, "ADMIN");
        clients.unavailable = true;
        JsonNode auth = service.detail(id).path("authorization");
        assertEquals("ACTOR_STATUS_UNAVAILABLE", auth.path("reasonCode").asText());
        assertFalse(auth.path("canTakeover").asBoolean());
        assertFalse(auth.path("canOperate").asBoolean());
    }

    private String waiting() {
        String id = fixture.create(UUID.randomUUID().toString(), -1);
        var run = fixture.store.claim();
        ObjectNode intent =
                AgentJson.object().put("id", "repair-call").put("name", "demo_config_restore");
        intent.set("arguments", AgentJson.object().put("expectedRevision", "a".repeat(64)));
        run.state().set("toolIntent", intent);
        fixture.store.requestApproval(run, intent, "WAITING_APPROVAL");
        return id;
    }

    private void decide(JsonNode row, String reason) {
        service.decide(
                row.path("id").asText(),
                row.path("revision").asInt(),
                row.path("args_hash").asText(),
                true,
                reason);
    }

    private static void actor(long id, String role) {
        var principal = new OpsPrincipal(id, "actor", "session", List.of(role));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code, assertThrows(AgentAccessFailure.class, call).reasonCode());
    }

    /**
     * @author heyu
     */
    private static class AccessClients extends AgentTestSupport.FakeClients {
        String inactive = "";
        boolean unavailable;
        final ObjectNode target =
                AgentJson.object()
                        .put("scope", "ISOLATED_DEMO")
                        .put("targetCode", AgentTargets.ORDER)
                        .put("incidentId", "incident-1")
                        .put("status", "FAULT_ACTIVE")
                        .put("configurationStatus", "APPLIED")
                        .put("expectedRevision", "a".repeat(64));

        @Override
        Context refresh(Context actor) {
            if (actor.userId() < 0 && !inactive.isBlank()) throw new AgentAccessFailure(inactive);
            return actor;
        }

        @Override
        JsonNode call(String audience, String path, String method, JsonNode body, Context actor) {
            if (audience.equals("auth")) {
                if (unavailable) throw new IllegalStateException("offline");
                ObjectNode identity =
                        AgentJson.object()
                                .put("userId", actor.userId())
                                .put("active", inactive.isBlank())
                                .put("reasonCode", inactive)
                                .put("expiresAt", Instant.now().plusSeconds(600).toString());
                identity.set("roles", AgentJson.tree(actor.roles()));
                return identity;
            }
            if (path.endsWith("/snapshot")) return target.deepCopy();
            if (path.endsWith("/workspace-context"))
                return AgentJson.object()
                        .put("id", 7)
                        .put("environment", "ISOLATED")
                        .put("affectedCiCode", AgentTargets.ORDER)
                        .put("incidentId", "incident-1")
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
            return super.call(audience, path, method, body, actor);
        }
    }
}
