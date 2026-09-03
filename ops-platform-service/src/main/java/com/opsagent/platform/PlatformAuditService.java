package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

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
        repository.addNotification(
                eventId,
                ticketId,
                actorId > 0 ? actorId : null,
                notificationTitle(operation),
                "工单 #" + ticketId + " 已执行“" + operation + "”操作，请关注后续状态。");
        return true;
    }

    List<Map<String, Object>> audits(String bizId, int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), 200);
        return repository.list(bizId, limit);
    }

    Map<String, Object> auditPage(
            String bizId, String requestedOperation, int requestedPage, int requestedSize) {
        int page = Math.max(requestedPage, 1);
        int size = Math.min(Math.max(requestedSize, 1), 100);
        String operation = normalize(requestedOperation);
        return page(
                repository.page(bizId, operation, (page - 1) * size, size),
                repository.count(bizId, operation),
                page,
                size);
    }

    Map<String, Object> notificationPage(
            String requestedStatus, int requestedPage, int requestedSize) {
        int page = Math.max(requestedPage, 1);
        int size = Math.min(Math.max(requestedSize, 1), 100);
        String status = normalizeNotificationStatus(requestedStatus, true);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", repository.notifications(status, (page - 1) * size, size));
        result.put("total", repository.notificationCount(status));
        result.put("pageNum", page);
        result.put("pageSize", size);
        result.put("unreadTotal", repository.notificationCount("UNREAD"));
        return result;
    }

    Map<String, Object> updateNotification(long id, String requestedStatus) {
        String status = normalizeNotificationStatus(requestedStatus, false);
        if (repository.updateNotificationStatus(id, status) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "通知不存在");
        }
        return repository.notification(id);
    }

    Map<String, Object> markAllNotificationsRead() {
        int updated = repository.updateAllNotificationStatus("UNREAD", "READ");
        return Map.of("updated", updated, "unreadTotal", 0);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNotificationStatus(String value, boolean allowEmpty) {
        String status = normalize(value);
        if (allowEmpty && status == null) return null;
        if (!"READ".equals(status) && !"UNREAD".equals(status)) {
            throw new BusinessException(ErrorCode.VALIDATION, "通知状态仅支持 READ 或 UNREAD");
        }
        return status;
    }

    private Map<String, Object> page(
            List<Map<String, Object>> records, long total, int page, int size) {
        return Map.of("records", records, "total", total, "pageNum", page, "pageSize", size);
    }

    private String notificationTitle(String operation) {
        return switch (operation) {
            case "CREATED" -> "新工单已创建";
            case "CLAIMED", "CLAIM" -> "工单已被接收";
            case "PROCESSING" -> "工单开始处理";
            case "WAITING_CONFIRM" -> "工单等待业务确认";
            case "RESOLVED" -> "工单已解决";
            case "CLOSED" -> "工单已关闭";
            default -> "工单状态已更新";
        };
    }
}
