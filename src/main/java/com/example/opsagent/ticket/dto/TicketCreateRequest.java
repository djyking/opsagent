package com.example.opsagent.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TicketCreateRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String description;

    @NotBlank
    private String priority;

    @NotBlank
    private String creator;

    private String assignee;
}
