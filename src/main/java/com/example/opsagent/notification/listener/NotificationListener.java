package com.example.opsagent.notification.listener;

import com.example.opsagent.ticket.event.TicketStatusChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener {

    @EventListener
    public void onTicketStatusChanged(TicketStatusChangedEvent event) {
        // Write notification_record here.
    }
}
