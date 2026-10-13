package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens.Context;

import org.junit.jupiter.api.Test;

/**
 * 模型证据去除预设答案；第二目标仍受固定工具、精确审批和真实队列恢复门禁约束。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentEvidenceContractTest {
    @Test
    void nativeModelRequestsReceiveMeasurementsAndChangesWithoutInjectedScenarioAnswers() {
        var fixture = new AgentTestSupport();
        var clients = new EvidenceClients();
        clients.model =
                request ->
                        switch (clients.modelRequests.size()) {
                            case 1 -> AgentTestSupport.response("ticket_get", AgentJson.object());
                            case 2 ->
                                    AgentTestSupport.response(
                                            "demo_target_inspect", AgentJson.object());
                            case 3 ->
                                    AgentTestSupport.response("recent_changes", AgentJson.object());
                            default -> AgentTestSupport.finalResponse();
                        };
        String id = fixture.create("unbiased-evidence", -1);
        var runtime = new AgentRuntime(fixture.store, clients, new AgentTools(clients), true);
        for (int i = 0; i < 15; i++) {
            fixture.jdbc.update("UPDATE agent_run SET next_attempt=TIMESTAMPADD(SECOND,-1,NOW(3))");
            runtime.tick();
        }
        assertTrue(clients.modelRequests.size() >= 4);
        String requests = clients.modelRequests.toString();
        assertFalse(requests.contains("NACOS_REDIS_CONFIG_DRIFT"));
        assertFalse(requests.contains("SENTINEL_RULE_REGRESSION"));
        assertFalse(requests.contains("PRESEEDED_CORRECT_ANSWER"));
        assertTrue(requests.contains("business.httpStatus=503"));
        assertTrue(requests.contains("after.redisPort=6380"));
        assertTrue(requests.contains("knownFacts"));
        assertTrue(requests.contains("candidateCauses"));
        assertTrue(requests.contains("evidenceGaps"));
        assertTrue(
                fixture.store
                        .get(id)
                        .state()
                        .path("observations")
                        .toString()
                        .contains("PRESEEDED_CORRECT_ANSWER"));
    }

    @Test
    void queueToolNeedsExactApprovalAndReplaysBoundTargetRevisionAndIdempotency() {
        var fixture = new AgentTestSupport();
        var clients = new EvidenceClients();
        clients.model =
                request ->
                        AgentTestSupport.response(
                                "demo_queue_restore",
                                AgentJson.object().put("expectedRevision", "a".repeat(64)));
        String id = fixture.create("queue-approval", -1);
        var initial = fixture.store.get(id);
        initial.state().put("targetCode", AgentTargets.NOTIFICATION);
        initial.snapshot().set("tools", AgentTools.schemas(AgentTargets.NOTIFICATION));
        fixture.jdbc.update(
                "UPDATE agent_run SET state_json=?,snapshot_json=? WHERE id=?",
                initial.state().toString(),
                initial.snapshot().toString(),
                id);
        var runtime = new AgentRuntime(fixture.store, clients, new AgentTools(clients), true);
        for (int i = 0; i < 5; i++) {
            fixture.jdbc.update("UPDATE agent_run SET next_attempt=TIMESTAMPADD(SECOND,-1,NOW(3))");
            runtime.tick();
        }
        assertEquals("WAITING_APPROVAL", fixture.store.get(id).status());
        assertEquals(0, clients.actions);
        JsonNode approval = fixture.store.approvals(id).get(0);
        assertThrows(
                BusinessException.class,
                () ->
                        fixture.store.decide(
                                approval.path("id").asText(), 1, "b".repeat(64), true, "同意", -1));
        assertEquals(0, clients.actions);
        fixture.store.decide(
                approval.path("id").asText(),
                1,
                approval.path("args_hash").asText(),
                true,
                "同意",
                -1);
        fixture.jdbc.update("UPDATE agent_run SET next_attempt=TIMESTAMPADD(SECOND,-1,NOW(3))");
        runtime.tick();
        assertEquals(1, clients.actions);
        assertEquals("/internal/platform/demo-targets/notification/actions", clients.actionPath);
        assertEquals("RESTORE_QUEUE_CONSUMER", clients.actionBody.path("action").asText());
        assertEquals("a".repeat(64), clients.actionBody.path("expectedRevision").asText());
        assertEquals("incident-1", clients.actionBody.path("incidentId").asText());
        assertTrue(clients.actionBody.path("idempotencyKey").asText().startsWith(id + ":"));
        JsonNode call = approval.path("payload");
        new AgentTools(clients)
                .execute(
                        fixture.store.get(id),
                        call,
                        clients.fromState(fixture.store.get(id).state()));
        assertEquals(clients.actionBody, clients.previousActionBody);
    }

    @Test
    void queueRestoreCannotBeSelectedForAnOrderRunAndCannotAcceptArbitraryQueue() {
        var fixture = new AgentTestSupport();
        String id = fixture.create("order-tool-scope", -1);
        var run = fixture.store.get(id);
        ObjectNode call = AgentJson.object().put("name", "demo_queue_restore").put("id", "queue");
        call.set("arguments", AgentJson.object().put("expectedRevision", "a".repeat(64)));
        assertThrows(
                BusinessException.class,
                () ->
                        new AgentTools(fixture.clients)
                                .execute(run, call, fixture.clients.fromState(run.state())));
        assertThrows(
                BusinessException.class,
                () ->
                        AgentTools.validate(
                                "demo_queue_restore",
                                AgentJson.object()
                                        .put("expectedRevision", "a".repeat(64))
                                        .put("queue", "foreign-queue")));
        assertFalse(
                AgentTools.schemas(AgentTargets.ORDER).toString().contains("demo_queue_restore"));
        assertFalse(
                AgentTools.schemas(AgentTargets.NOTIFICATION)
                        .toString()
                        .contains("demo_config_restore"));
        assertEquals(0, fixture.clients.actions);
    }

    @Test
    void successfulNotificationHttpStillRequiresDrainedQueueAndLiveConsumer() {
        ObjectNode snapshot = AgentJson.object();
        snapshot.set(
                "business",
                AgentJson.object()
                        .put("httpStatus", 200)
                        .put("queueDrained", true)
                        .put("messageId", "4f63c17d-8876-498a-a3de-3100768a9cc8")
                        .put("deliveredAt", java.time.Instant.now().toString())
                        .put("receiptStore", "ISOLATED_REDIS_READ_BACK")
                        .put("reasonCode", "NOTIFICATION_DELIVERED"));
        snapshot.set(
                "queue",
                AgentJson.object()
                        .put("queue", "opsagent.demo.notification.v1")
                        .put("vhost", "notifications")
                        .put("messagesReady", 4)
                        .put("consumerCount", 1));
        assertFalse(AgentTools.queueReady(snapshot, AgentTargets.NOTIFICATION));
        ((ObjectNode) snapshot.path("queue")).put("messagesReady", 0).put("consumerCount", 0);
        assertFalse(AgentTools.queueReady(snapshot, AgentTargets.NOTIFICATION));
        ((ObjectNode) snapshot.path("queue")).put("consumerCount", 1);
        assertTrue(AgentTools.queueReady(snapshot, AgentTargets.NOTIFICATION));
        ((ObjectNode) snapshot.path("business")).put("queueDrained", false);
        assertFalse(AgentTools.queueReady(snapshot, AgentTargets.NOTIFICATION));
    }

    @Test
    void structuredDiagnosisIsDurableOnlyAfterTicketAnalysisWriteCommits() {
        var fixture = new AgentTestSupport();
        fixture.clients.model =
                request ->
                        AgentTestSupport.response(
                                "ticket_add_analysis",
                                AgentJson.object()
                                        .put("summary", "候选配置问题")
                                        .put("evidence", "探针HTTP503")
                                        .put("recommendation", "先核验近期变更")
                                        .put("knownFacts", "连接失败")
                                        .put("candidateCauses", "近期端口变更")
                                        .put("evidenceGaps", "缺少发布前的探针"));
        String id = fixture.create("diagnosis-write", -1);
        for (int i = 0; i < 5; i++) fixture.step();
        assertFalse(fixture.store.get(id).state().has("diagnosis"));
        fixture.step();
        assertEquals(
                "近期端口变更",
                fixture.store.get(id).state().path("diagnosis").path("candidateCauses").asText());
        assertTrue(
                fixture.clients
                        .writes
                        .get(0)
                        .path("input")
                        .path("summary")
                        .asText()
                        .contains("证据缺口：缺少发布前的探针"));
    }

    /**
     * @author heyu
     */
    private static final class EvidenceClients extends AgentTestSupport.FakeClients {
        String actionPath;
        JsonNode actionBody;
        JsonNode previousActionBody;

        @Override
        JsonNode call(String audience, String path, String method, JsonNode body, Context actor) {
            if (path.endsWith("/evidence")) {
                ObjectNode result =
                        AgentJson.object()
                                .put("incidentId", "incident-1")
                                .put("targetCode", actor.targetCode());
                ObjectNode change =
                        AgentJson.object()
                                .put("id", "real-change")
                                .put("kind", "CONFIGURATION_APPLIED")
                                .put("summary", "PRESEEDED_CORRECT_ANSWER");
                change.set("before", AgentJson.object().put("redisPort", 6379));
                change.set(
                        "after",
                        AgentJson.object()
                                .put("redisPort", 6380)
                                .put("scenarioCode", "NACOS_REDIS_CONFIG_DRIFT"));
                result.putArray("changes").add(change);
                return result;
            }
            if (path.endsWith("/actions")) {
                previousActionBody = actionBody;
                actionBody = body.deepCopy();
                actionPath = path;
            }
            JsonNode result = super.call(audience, path, method, body, actor);
            if (path.equals("/internal/agent/tickets/7")) {
                ((ObjectNode) result)
                        .put("environment", "ISOLATED")
                        .put("title", "NACOS_REDIS_CONFIG_DRIFT")
                        .put("description", "PRESEEDED_CORRECT_ANSWER");
            }
            if (path.endsWith("/snapshot")) {
                ((ObjectNode) result)
                        .put("scenarioCode", "SENTINEL_RULE_REGRESSION")
                        .put("expectedAction", "PRESEEDED_CORRECT_ANSWER");
                ((ObjectNode) result.path("business")).put("httpStatus", 503);
            }
            return result;
        }
    }
}
