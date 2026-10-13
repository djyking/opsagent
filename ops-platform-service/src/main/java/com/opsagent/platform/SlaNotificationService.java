package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将 SLA 提醒投递给当前实际值班账号；每个事件和收件人只产生一条站内通知。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class SlaNotificationService {
    private final PlatformAuditRepository audit;
    private final ItsmPlatformRepository oncall;

    SlaNotificationService(PlatformAuditRepository audit, ItsmPlatformRepository oncall) {
        this.audit = audit;
        this.oncall = oncall;
    }

    @Transactional
    boolean record(JsonNode event) {
        String eventId = event.path("eventId").asText();
        String type = event.path("eventType").asText();
        JsonNode payload = event.path("payload");
        long ticket = payload.path("ticketId").asLong();
        if (eventId.isBlank()
                || eventId.length() > 128
                || !Set.of(
                                "sla.response_warning",
                                "sla.response_breach",
                                "sla.resolution_warning",
                                "sla.resolution_breach",
                                "sla.escalation")
                        .contains(type)
                || ticket <= 0) throw new IllegalArgumentException("SLA 通知事件格式无效");
        int level = payload.path("escalationLevel").asInt();
        String service = payload.path("serviceCiCode").asText();
        if (!service.isBlank() && !service.matches("[A-Za-z0-9_.-]{1,128}"))
            throw new IllegalArgumentException("SLA 服务关联格式无效");
        var members = oncall.currentOnCall(service).members();
        if (service.isBlank())
            members =
                    members.stream()
                            .filter(
                                    member ->
                                            member.get("serviceCiCode") == null
                                                    || String.valueOf(member.get("serviceCiCode"))
                                                            .isBlank())
                            .toList();
        var recipients = recipients(members, level);
        // Keep the message pending/retryable instead of pretending a person received it.
        if (recipients.isEmpty()) throw new IllegalStateException("当前缺少有效值班收件人");
        if (audit.consumeOnce("platform-sla-notification", eventId) == 0) return false;
        String title =
                switch (type) {
                    case "sla.response_warning" -> "响应 SLA 即将到期";
                    case "sla.response_breach" -> "响应 SLA 已超时";
                    case "sla.resolution_warning" -> "解决 SLA 即将到期";
                    case "sla.resolution_breach" -> "解决 SLA 已超时";
                    default -> level >= 2 ? "二级 SLA 升级待处理" : "一级 SLA 升级待处理";
                };
        for (long receiver : recipients) {
            audit.addNotification(
                    sourceKey(eventId, receiver),
                    ticket,
                    receiver,
                    title,
                    "事件 #"
                            + ticket
                            + (service.isBlank() ? "" : " · " + service)
                            + "："
                            + title
                            + "。请进入事件核对当前阶段并处理。");
        }
        audit.add(
                "SLA_INBOX_DELIVERED",
                Long.toString(ticket),
                null,
                eventId,
                "{\"channel\":\"IN_APP\",\"recipientCount\":" + recipients.size() + "}");
        return true;
    }

    static Set<Long> recipients(List<Map<String, Object>> members, int level) {
        Set<Long> result = new LinkedHashSet<>();
        for (var member : members) {
            String role = String.valueOf(member.get("roleType"));
            if (!("PRIMARY".equals(role) || level >= 2 && "SECONDARY".equals(role))) continue;
            Object value = member.get("userId");
            if (value instanceof Number number && number.longValue() > 0)
                result.add(number.longValue());
        }
        return result;
    }

    private static String sourceKey(String event, long receiver) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (event + ":" + receiver)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
