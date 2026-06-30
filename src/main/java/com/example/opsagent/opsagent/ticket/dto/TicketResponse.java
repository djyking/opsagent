package com.example.opsagent.opsagent.ticket.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketResponse {

    private Long id;

    private String title;

    private String description;

    private String status;

    private String priority;

    private String sourceSystem;

    private String assignee;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
