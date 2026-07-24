package com.example.opsagent.audit.listener;

import com.example.opsagent.ticket.event.TicketStatusChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AuditLogListener {

    @EventListener
    public void onTicketStatusChanged(TicketStatusChangedEvent event) {
        // Write operation_log here.
    }
}
