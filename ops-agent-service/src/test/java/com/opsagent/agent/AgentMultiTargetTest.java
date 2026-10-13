package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens.Context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

/**
 * 通知恢复必须包含真实投递回执与固定隔离队列证据，保留用于收口的同次探针。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentMultiTargetTest {
    @ParameterizedTest
    @ValueSource(strings = {"messageId", "deliveredAt", "receiptStore", "reasonCode"})
    void missingDeliveryReceiptNeverAllowsTicketCloseout(String field) {
        var fixture = new AgentTestSupport();
        var clients = new NotificationClients();
        ((ObjectNode) clients.snapshot.path("business")).remove(field);
        String id = fixture.create("missing-receipt-" + field, -1);
        var run = fixture.store.get(id);
        run.state().put("targetCode", AgentTargets.NOTIFICATION);
        run.snapshot().set("tools", AgentTools.schemas(AgentTargets.NOTIFICATION));

        JsonNode prepared =
                new AgentTools(clients).prepare(run, resolveCall(), clients.fromState(run.state()));

        assertEquals("BUSINESS_PENDING", prepared.path("observation").path("reasonCode").asText());
        assertFalse(prepared.has("body"));
        assertEquals(0, clients.ticketReads);
        assertEquals(0, clients.episodeReads);
        assertTrue(clients.writes.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "business,messageId,not-a-message-id",
        "business,deliveredAt,not-an-instant",
        "business,receiptStore,IN_MEMORY",
        "business,reasonCode,NOTIFICATION_PENDING",
        "queue,queue,foreign-queue",
        "queue,vhost,foreign-vhost"
    })
    void malformedOrForeignDeliveryEvidenceIsRejected(String object, String field, String value) {
        ObjectNode snapshot = snapshot();
        ObjectNode target =
                (ObjectNode)
                        (object.equals("business")
                                ? snapshot.path("business")
                                : snapshot.path("business").path("queue"));
        target.put(field, value);
        assertFalse(AgentTools.queueReady(snapshot, AgentTargets.NOTIFICATION));
    }

    @Test
    void closeoutEvidencePreservesReceiptAndUsesQueueFromSameBusinessProbe() {
        var fixture = new AgentTestSupport();
        var clients = new NotificationClients();
        String id = fixture.create("notification-receipt", -1);
        var run = fixture.store.get(id);
        run.state().put("targetCode", AgentTargets.NOTIFICATION);
        run.snapshot().set("tools", AgentTools.schemas(AgentTargets.NOTIFICATION));
        clients.snapshot.set("queue", AgentJson.object().put("messagesReady", 99));
        assertTrue(AgentTools.queueReady(clients.snapshot, AgentTargets.NOTIFICATION));

        JsonNode prepared =
                new AgentTools(clients).prepare(run, resolveCall(), clients.fromState(run.state()));

        assertEquals("RESOLVED", prepared.path("body").path("input").path("toStatus").asText());
        JsonNode evidence = prepared.path("evidence");
        JsonNode business = clients.snapshot.path("business");
        for (String field :
                new String[] {"messageId", "deliveredAt", "receiptStore", "reasonCode"}) {
            assertEquals(business.path(field), evidence.path("business").path(field));
        }
        assertEquals(business.path("queue"), evidence.path("queue"));
        ((ObjectNode) clients.snapshot.path("business").path("queue")).put("messagesReady", 88);
        assertEquals(0, evidence.path("queue").path("messagesReady").asInt());
        assertFalse(AgentTools.queueReady(clients.snapshot, AgentTargets.NOTIFICATION));
    }

    private static ObjectNode resolveCall() {
        ObjectNode call =
                AgentJson.object().put("id", "verify:close").put("name", "ticket_resolve");
        call.set("arguments", AgentJson.object().put("comment", "核验通知业务恢复"));
        return call;
    }

    private static ObjectNode snapshot() {
        ObjectNode snapshot =
                AgentJson.object()
                        .put("incidentId", "incident-1")
                        .put("targetCode", AgentTargets.NOTIFICATION)
                        .put("recoverySource", "AGENT_TOOL")
                        .put("expectedRevision", "a".repeat(64));
        ObjectNode business =
                AgentJson.object()
                        .put("httpStatus", 200)
                        .put("consecutiveSuccesses", 3)
                        .put("observedAt", Instant.now().toString())
                        .put("queueDrained", true)
                        .put("messageId", "4f63c17d-8876-498a-a3de-3100768a9cc8")
                        .put("deliveredAt", Instant.now().toString())
                        .put("receiptStore", "ISOLATED_REDIS_READ_BACK")
                        .put("reasonCode", "NOTIFICATION_DELIVERED");
        business.set(
                "queue",
                AgentJson.object()
                        .put("queue", "opsagent.demo.notification.v1")
                        .put("vhost", "notifications")
                        .put("messagesReady", 0)
                        .put("consumerCount", 1));
        snapshot.set("business", business);
        return snapshot;
    }

    /**
     * @author heyu
     */
    private static final class NotificationClients extends AgentTestSupport.FakeClients {
        private final ObjectNode snapshot = AgentMultiTargetTest.snapshot();
        private int episodeReads;

        @Override
        JsonNode call(String audience, String path, String method, JsonNode body, Context actor) {
            if (path.equals("/internal/platform/demo-targets/notification/snapshot"))
                return snapshot;
            if (path.contains("/alerts/")) episodeReads++;
            return super.call(audience, path, method, body, actor);
        }
    }
}
