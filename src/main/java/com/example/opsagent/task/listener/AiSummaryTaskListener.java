package com.example.opsagent.task.listener;

import com.example.opsagent.ticket.event.TicketStatusChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AiSummaryTaskListener {

    @EventListener
    public void onTicketStatusChanged(TicketStatusChangedEvent event) {
        // Create ai_task when event.getToStatus() is RESOLVED.
    }
}
