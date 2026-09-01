package com.opsagent.ticket;
import java.util.Set;
public enum TicketStatus {
    CREATED(Set.of("ASSIGNED", "REJECTED")),
    ASSIGNED(Set.of("PROCESSING", "REJECTED")),
    PROCESSING(Set.of("SUSPENDED", "WAITING_CONFIRM", "RESOLVED", "REJECTED")),
    SUSPENDED(Set.of("PROCESSING", "REJECTED")),
    WAITING_CONFIRM(Set.of("PROCESSING", "RESOLVED")),
    RESOLVED(Set.of("PROCESSING", "CLOSED")),
    CLOSED(Set.of()), REJECTED(Set.of());
    private final Set<String> targets;
    TicketStatus(Set<String> targets) { this.targets = targets; }
    public boolean allows(TicketStatus target) { return targets.contains(target.name()); }
}
