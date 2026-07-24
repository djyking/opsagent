package com.example.opsagent.ticket.dto;

import lombok.Data;

@Data
public class TicketQueryRequest {

    private String status;

    private String priority;

    private String keyword;

    private Long pageNum = 1L;

    private Long pageSize = 10L;
}
