package com.example.opsagent.ticket.dto;

import lombok.Data;

@Data
public class TicketUpdateRequest {

    private String title;

    private String description;

    private String priority;

    private String assignee;
}
