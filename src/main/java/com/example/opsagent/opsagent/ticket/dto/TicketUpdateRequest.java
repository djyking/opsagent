package com.example.opsagent.opsagent.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TicketUpdateRequest {

    @NotNull
    private Long id;

    @NotBlank
    @Size(max = 100)
    private String title;

    @Size(max = 2000)
    private String description;

    @NotBlank
    @Size(max = 32)
    private String status;

    @NotBlank
    @Size(max = 32)
    private String priority;

    @Size(max = 64)
    private String sourceSystem;

    @Size(max = 64)
    private String assignee;
}
