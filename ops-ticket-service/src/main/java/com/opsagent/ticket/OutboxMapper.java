package com.opsagent.ticket;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在业务事务内写入待发布事件，供后续 Outbox Publisher 投递。
 *
 * @author heyu
 * @since 2026/8/7
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

    @Select(
            """
            SELECT id, event_id eventId, aggregate_id aggregateId, event_type eventType,
                   payload, retry_count retryCount, create_time createTime
            FROM event_outbox
            WHERE status IN ('PENDING', 'FAILED')
              AND (next_retry_time IS NULL OR next_retry_time <= NOW())
            ORDER BY id
            LIMIT #{limit}
            """)
    List<OutboxEvent> pending(int limit);

    @Update(
            """
            UPDATE event_outbox
            SET status='PUBLISHING', update_time=NOW()
            WHERE id=#{id} AND status IN ('PENDING', 'FAILED')
            """)
    int claim(long id);

    @Update(
            """
            UPDATE event_outbox
            SET status='FAILED', next_retry_time=NOW(), update_time=NOW()
            WHERE status='PUBLISHING'
              AND update_time < TIMESTAMPADD(SECOND, -#{staleSeconds}, NOW())
            """)
    int recoverStalePublishing(long staleSeconds);

    @Update(
            """
            UPDATE event_outbox
            SET status='SENT', next_retry_time=NULL, update_time=NOW()
            WHERE id=#{id} AND status='PUBLISHING'
            """)
    int sent(long id);

    @Update(
            """
            UPDATE event_outbox
            SET status='FAILED', retry_count=retry_count+1,
                next_retry_time=DATE_ADD(NOW(), INTERVAL LEAST(300, POW(2, retry_count + 1)) SECOND),
                update_time=NOW()
            WHERE id=#{id} AND status='PUBLISHING'
            """)
    int failed(long id);

    /**
     * 待发布的工单领域事件快照。
     *
     * @author heyu
     * @since 2026/8/8
     */
    record OutboxEvent(
            long id,
            String eventId,
            long aggregateId,
            String eventType,
            String payload,
            int retryCount,
            LocalDateTime createTime) {}
}
