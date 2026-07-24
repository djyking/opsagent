package com.example.opsagent.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TicketStatusUpdateRequest {

    @NotBlank
    private String targetStatus;

    @NotBlank
    private String operator;

    private String reason;
}
