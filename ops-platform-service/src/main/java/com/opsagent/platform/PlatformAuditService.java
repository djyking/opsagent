package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 在单一数据库事务内完成事件去重和平台操作审计落库。
 *
 * @author heyu
 * @since 2026/9/1
 */
@Service
public class PlatformAuditService {
    private static final String CONSUMER = "platform-ticket-audit";
    private final PlatformAuditRepository repository;

    PlatformAuditService(PlatformAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    boolean record(JsonNode event) {
        String eventId = event.path("eventId").asText();
        if (eventId.isBlank()) throw new IllegalArgumentException("事件缺少 eventId");
        if (repository.consumeOnce(CONSUMER, eventId) == 0) return false;

        String eventType = event.path("eventType").asText();
        JsonNode payload = event.path("payload");
        long ticketId = payload.path("ticketId").asLong();
        if (ticketId <= 0) throw new IllegalArgumentException("工单事件缺少 ticketId");
        long actorId = payload.path("actorId").asLong();
        String operation =
                eventType.contains(".")
                        ? eventType.substring(eventType.lastIndexOf('.') + 1)
                                .toUpperCase(Locale.ROOT)
                        : eventType.toUpperCase(Locale.ROOT);
        repository.add(
                operation,
                Long.toString(ticketId),
                actorId > 0 ? actorId : null,
                eventId,
                payload.toString());
        return true;
    }

    List<Map<String, Object>> audits(String bizId, int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), 200);
        return repository.list(bizId, limit);
    }
}
