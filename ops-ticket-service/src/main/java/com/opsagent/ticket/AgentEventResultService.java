package com.opsagent.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 只把受控 AI 修复的机器证据登记为处理结果，不代替人的恢复确认。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class AgentEventResultService {
    private final TicketMapper tickets;
    private final TicketAuditMapper audit;
    private final AlertEpisodeMapper episodes;
    private final ObjectMapper json;

    AgentEventResultService(
            TicketMapper tickets,
            TicketAuditMapper audit,
            AlertEpisodeMapper episodes,
            ObjectMapper json) {
        this.tickets = tickets;
        this.audit = audit;
        this.episodes = episodes;
        this.json = json;
    }

    void validate(Ticket ticket, JsonNode result) {
        JsonNode repair = result.path("approvedRepair");
        JsonNode evidence = result.path("evidence");
        JsonNode business = evidence.path("business");
        var episode = ticket.getEpisodeId() == null ? null : episodes.lock(ticket.getEpisodeId());
        String target = ticket.getAffectedCiCode();
        String tool = repair.path("tool").asText();
        boolean knownRepair =
                "ops-demo-notification-service".equals(target)
                        ? "demo_queue_restore".equals(tool)
                        : "ops-demo-order-service".equals(target)
                                && Set.of("demo_config_restore", "demo_flow_restore")
                                        .contains(tool);
        if (!"ISOLATED".equals(ticket.getEnvironment())
                || !Set.of("PROCESSING", "WAITING_CONFIRM", "RESOLVED").contains(ticket.getStatus())
                || !knownRepair
                || !result.isObject()
                || !"AGENT_TOOL".equals(evidence.path("recoverySource").asText())
                || ticket.getIncidentId() == null
                || !ticket.getIncidentId().equals(repair.path("incidentId").asText())
                || !ticket.getIncidentId().equals(evidence.path("incidentId").asText())
                || !target.equals(repair.path("targetCode").asText())
                || !target.equals(evidence.path("targetCode").asText())
                || repair.path("approvalId").asText().isBlank()
                || !repair.path("approvalHash").asText().matches("[a-f0-9]{64}")
                || !repair.path("revisionAfter").asText().matches("[a-f0-9]{64}")
                || !repair.path("revisionAfter")
                        .asText()
                        .equals(evidence.path("expectedRevision").asText())
                || repair.path("revisionBefore")
                        .asText()
                        .equals(repair.path("revisionAfter").asText())
                || episode == null
                || !"resolved".equals(episode.currentStatus())
                || !Objects.equals(episode.ticketId(), ticket.getId())
                || !ticket.getIncidentId().equals(episode.incidentId())
                || !ticket.getEpisodeId().equals(evidence.path("episodeId").asText())
                || !"resolved".equals(evidence.path("episodeStatus").asText())
                || business.path("httpStatus").asInt() != 200
                || business.path("consecutiveSuccesses").asInt() < 3
                || !freshAfterRepair(
                        business.path("observedAt").asText(), repair.path("observedAt").asText())
                || ("ops-demo-notification-service".equals(target) && !delivered(evidence))) {
            throw invalid();
        }
    }

    // The caller holds the ticket row lock, version check and effect transaction throughout.
    void record(Ticket ticket, InternalTicketTools.Call call, InternalActorTokens.Context actor) {
        var records = audit.workRecords(ticket.getId());
        var current = EventLifecycleService.reduce(records);
        if (current.result() != null || current.closed() != null || current.legacyArchived())
            return;
        // A later human failure/reopen must not be undone by another key for the same completed
        // run.
        if (records.stream().anyMatch(row -> sameRun(row, call.runId()))) return;
        if (!"RESOLVED".equals(ticket.getStatus())) throw invalid();
        JsonNode result = call.input().path("machineResult");
        JsonNode evidence = result.path("evidence");
        JsonNode repair = result.path("approvedRepair");
        var reference =
                json.createObjectNode()
                        .put("source", "AI_MACHINE_RESULT")
                        .put("runId", call.runId())
                        .put("incidentId", ticket.getIncidentId())
                        .put("targetCode", ticket.getAffectedCiCode())
                        .put("approvalId", repair.path("approvalId").asText())
                        .put("tool", repair.path("tool").asText())
                        .put("episodeId", ticket.getEpisodeId())
                        .put("observedAt", evidence.path("business").path("observedAt").asText())
                        .put("recoverySource", "AGENT_TOOL");
        String summary =
                "AI 处理结果 / 机器验证：已执行审批通过的受控修复，业务探针 HTTP 200，连续成功 "
                        + evidence.path("business").path("consecutiveSuccesses").asInt()
                        + " 次，关联告警已恢复。待有权限的处置人分别完成技术确认、业务确认与关闭。";
        if ("ops-demo-notification-service".equals(ticket.getAffectedCiCode())) {
            reference.put("messageId", evidence.path("business").path("messageId").asText());
            summary += "通知已取得真实投递回执，隔离队列已排空。";
        }
        if (reference.toString().length() > 1000) throw invalid();
        if (tickets.touch(ticket.getId(), ticket.getVersion()) != 1)
            throw new BusinessException(ErrorCode.CONFLICT, "事件版本已变化，未登记 AI 处理结果");
        audit.workRecord(ticket.getId(), "EVENT_RESULT", summary, reference.toString(), 0);
        var detail = result.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) detail)
                .put("source", "AI_MACHINE_RESULT")
                .put("runId", call.runId());
        audit.operation(
                ticket.getId(),
                actor.userId(),
                "EVENT_AI_RESULT",
                call.idempotencyKey(),
                detail.toString());
        ticket.setVersion(ticket.getVersion() + 1);
    }

    private boolean sameRun(TicketAuditMapper.WorkRecord row, String runId) {
        if (!"EVENT_RESULT".equals(row.recordType())
                || row.createBy() != 0
                || row.evidence() == null) return false;
        try {
            JsonNode evidence = json.readTree(row.evidence());
            return "AI_MACHINE_RESULT".equals(evidence.path("source").asText())
                    && runId.equals(evidence.path("runId").asText());
        } catch (Exception ordinaryEvidence) {
            return false;
        }
    }

    private boolean freshAfterRepair(String observedAt, String repairedAt) {
        try {
            Instant observed = Instant.parse(observedAt);
            Instant repaired = Instant.parse(repairedAt);
            Instant now = Instant.now();
            return observed.isAfter(now.minusSeconds(20))
                    && !observed.isAfter(now.plusSeconds(2))
                    && !observed.isBefore(repaired);
        } catch (RuntimeException invalidTimestamp) {
            return false;
        }
    }

    private boolean delivered(JsonNode evidence) {
        JsonNode business = evidence.path("business");
        JsonNode queue = evidence.path("queue");
        try {
            Instant.parse(business.path("deliveredAt").asText());
        } catch (RuntimeException invalidTimestamp) {
            return false;
        }
        return business.path("queueDrained").asBoolean()
                && business.path("messageId")
                        .asText()
                        .matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                && "ISOLATED_REDIS_READ_BACK".equals(business.path("receiptStore").asText())
                && "NOTIFICATION_DELIVERED".equals(business.path("reasonCode").asText())
                && "opsagent.demo.notification.v1".equals(queue.path("queue").asText())
                && "notifications".equals(queue.path("vhost").asText())
                && queue.path("messagesReady").isIntegralNumber()
                && queue.path("messagesReady").asLong(-1) == 0
                && queue.path("consumerCount").isIntegralNumber()
                && queue.path("consumerCount").asInt() >= 1;
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.CONFLICT, "缺少同一事件的受控 AI 修复及新鲜机器恢复证据，未登记处理结果");
    }
}
