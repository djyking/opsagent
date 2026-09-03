package com.opsagent.ticket;

import org.apache.ibatis.annotations.Insert;

/**
 * 在业务事务内写入待发布事件，供后续 Outbox Publisher 投递。
 *
 * @author heyu
 * @since 2026/9/2
 */
public interface OutboxMapper {
    @Insert(
            """
            INSERT INTO event_outbox(
                event_id,
                aggregate_type,
                aggregate_id,
                event_type,
                payload,
                status,
                retry_count,
                next_retry_time,
                create_time,
                update_time
            )
            VALUES(#{eventId}, 'TICKET', #{aggregateId}, #{eventType}, #{payload}, 'PENDING', 0, NOW(), NOW(), NOW())
            """)
    int add(String eventId, long aggregateId, String eventType, String payload);
}
