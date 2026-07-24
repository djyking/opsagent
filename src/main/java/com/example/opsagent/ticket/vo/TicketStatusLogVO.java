package com.example.opsagent.ticket.vo;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TicketStatusLogVO {

    private Long id;

    private Long ticketId;

    private String fromStatus;

    private String toStatus;

    private String operator;

    private String reason;

    private LocalDateTime createTime;
}
