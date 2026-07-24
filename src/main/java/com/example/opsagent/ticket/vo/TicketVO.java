package com.example.opsagent.ticket.vo;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TicketVO {

    private Long id;

    private String title;

    private String description;

    private String priority;

    private String status;

    private String creator;

    private String assignee;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
