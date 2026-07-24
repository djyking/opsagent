package com.example.opsagent.ticket.event;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TicketStatusChangedEvent {

    private Long ticketId;

    private String title;

    private String fromStatus;

    private String toStatus;

    private String operator;

    private String assignee;

    private String reason;

    private LocalDateTime changedAt;
}
