package com.example.opsagent.ticket.event;

import java.time.LocalDateTime;

/**
 * 表示工单主事务中已完成的状态变化事实。
 *
 * @author heyu
 * @since 2026/8/16
 */
public record TicketStatusChangedEvent(
    Long ticketId,
    String title,
    String fromStatus,
    String toStatus,
    Long operatorId,
    Long creatorId,
    Long assigneeId,
    String remark,
    LocalDateTime occurredAt
) {
}
