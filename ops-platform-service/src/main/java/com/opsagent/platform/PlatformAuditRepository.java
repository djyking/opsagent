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
}
