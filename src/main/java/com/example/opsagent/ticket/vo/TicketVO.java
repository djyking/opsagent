package com.example.opsagent.ticket.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 返回工单编号、状态及用户归属信息。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class TicketVO {

    private Long id;

    private String ticketNo;

    private String title;

    private String description;

    private String priority;

    private String status;

    private Long creatorId;

    private Long assigneeId;

    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
