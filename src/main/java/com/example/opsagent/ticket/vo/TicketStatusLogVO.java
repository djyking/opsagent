package com.example.opsagent.ticket.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 返回工单关键操作与状态变化记录。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class TicketStatusLogVO {

    private Long id;

    private Long ticketId;

    private Long operatorId;

    private String operationType;

    private String fromStatus;

    private String toStatus;

    private String remark;

    private LocalDateTime createTime;
}
