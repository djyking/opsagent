package com.example.opsagent.ticket.enums;

import java.util.Locale;
import java.util.Set;

/**
 * 工单状态及允许的状态流转规则。
 *
 * @author heyu
 * @since 2026/8/15
 */
public enum TicketStatus {
    OPEN(Set.of("PROCESSING")),
    PROCESSING(Set.of("RESOLVED")),
    RESOLVED(Set.of("PROCESSING", "CLOSED")),
    CLOSED(Set.of());

    private final Set<String> allowedTargets;

    TicketStatus(Set<String> allowedTargets) {
        this.allowedTargets = allowedTargets;
    }

    public boolean canTransitionTo(TicketStatus target) {
        return allowedTargets.contains(target.name());
    }

    public static TicketStatus parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("工单状态不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("工单状态只能是 OPEN、PROCESSING、RESOLVED 或 CLOSED");
        }
    }
}
