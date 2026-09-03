package com.opsagent.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 保存跨服务操作审计，并维护平台消费者的事件幂等记录。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Repository
public class PlatformAuditRepository {
    private final JdbcTemplate jdbc;

    PlatformAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int consumeOnce(String consumer, String eventId) {
        return jdbc.update(
                "INSERT IGNORE INTO mq_consumed_event(consumer_name,event_id,consume_time)"
                        + " VALUES(?,?,NOW())",
                consumer,
                eventId);
    }

    void add(
            String operation,
            String bizId,
            Long userId,
            String traceId,
            String detailJson) {
        jdbc.update(
                "INSERT INTO operation_audit(service_name,biz_type,biz_id,operation,user_id,"
                        + "trace_id,request_id,detail_json,create_time)"
                        + " VALUES('ops-ticket-service','TICKET',?,?,?,?,NULL,?,NOW())",
                bizId,
                operation,
                userId,
                traceId,
                detailJson);
    }

    void addNotification(
            String sourceKey, long ticketId, Long receiverId, String title, String content) {
        jdbc.update(
                "INSERT IGNORE INTO notification_record(source_key,ticket_id,receiver_id,title,"
                        + "content,status,create_time,update_time)"
                        + " VALUES(?,?,?,?,?,'UNREAD',NOW(),NOW())",
                sourceKey,
                ticketId,
                receiverId,
                title,
                content);
    }

    List<Map<String, Object>> list(String bizId, int limit) {
        if (bizId == null || bizId.isBlank()) {
            return jdbc.queryForList(
                    "SELECT id,service_name,biz_type,biz_id,operation,user_id,trace_id,"
                            + "detail_json,create_time FROM operation_audit ORDER BY id DESC LIMIT ?",
                    limit);
        }
        return jdbc.queryForList(
                "SELECT id,service_name,biz_type,biz_id,operation,user_id,trace_id,"
                        + "detail_json,create_time FROM operation_audit WHERE biz_id=?"
                        + " ORDER BY id DESC LIMIT ?",
                bizId,
                limit);
    }

    long count(String bizId, String operation) {
        boolean hasBizId = bizId != null && !bizId.isBlank();
        boolean hasOperation = operation != null && !operation.isBlank();
        if (!hasBizId && !hasOperation) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit", Long.class);
        }
        if (hasBizId && hasOperation) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM operation_audit WHERE biz_id=? AND operation=?",
                    Long.class,
                    bizId,
                    operation);
        }
        if (hasOperation) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM operation_audit WHERE operation=?",
                    Long.class,
                    operation);
        }
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation_audit WHERE biz_id=?", Long.class, bizId);
    }

    List<Map<String, Object>> page(String bizId, String operation, int offset, int limit) {
        boolean hasBizId = bizId != null && !bizId.isBlank();
        boolean hasOperation = operation != null && !operation.isBlank();
        if (!hasBizId && !hasOperation) {
            return jdbc.queryForList(
                    "SELECT id,service_name,biz_type,biz_id,operation,user_id,trace_id,"
                            + "detail_json,create_time FROM operation_audit ORDER BY id DESC"
                            + " LIMIT ? OFFSET ?",
                    limit,
                    offset);
        }
        if (hasBizId && hasOperation) {
            return jdbc.queryForList(
                    "SELECT id,service_name,biz_type,biz_id,operation,user_id,trace_id,"
                            + "detail_json,create_time FROM operation_audit WHERE biz_id=?"
                            + " AND operation=? ORDER BY id DESC LIMIT ? OFFSET ?",
                    bizId,
                    operation,
                    limit,
                    offset);
        }
        if (hasOperation) {
            return jdbc.queryForList(
                    "SELECT id,service_name,biz_type,biz_id,operation,user_id,trace_id,"
                            + "detail_json,create_time FROM operation_audit WHERE operation=?"
                            + " ORDER BY id DESC LIMIT ? OFFSET ?",
                    operation,
                    limit,
                    offset);
        }
        return jdbc.queryForList(
                "SELECT id,service_name,biz_type,biz_id,operation,user_id,trace_id,"
                        + "detail_json,create_time FROM operation_audit WHERE biz_id=?"
                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                bizId,
                limit,
                offset);
    }

    long notificationCount(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM notification_record", Long.class);
        }
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_record WHERE status=?", Long.class, status);
    }

    List<Map<String, Object>> notifications(String status, int offset, int limit) {
        if (status != null && !status.isBlank()) {
            return jdbc.queryForList(
                    "SELECT id,ticket_id,receiver_id,title,content,status,create_time,update_time"
                            + " FROM notification_record WHERE status=? ORDER BY id DESC"
                            + " LIMIT ? OFFSET ?",
                    status,
                    limit,
                    offset);
        }
        return jdbc.queryForList(
                "SELECT id,ticket_id,receiver_id,title,content,status,create_time,update_time"
                        + " FROM notification_record ORDER BY id DESC LIMIT ? OFFSET ?",
                limit,
                offset);
    }

    int updateAllNotificationStatus(String sourceStatus, String targetStatus) {
        return jdbc.update(
                "UPDATE notification_record SET status=?,update_time=NOW() WHERE status=?",
                targetStatus,
                sourceStatus);
    }

    int updateNotificationStatus(long id, String status) {
        return jdbc.update(
                "UPDATE notification_record SET status=?,update_time=NOW() WHERE id=?",
                status,
                id);
    }

    Map<String, Object> notification(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,ticket_id,receiver_id,title,content,status,create_time,update_time"
                        + " FROM notification_record WHERE id=?",
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
