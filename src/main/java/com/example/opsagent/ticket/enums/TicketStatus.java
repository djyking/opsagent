package com.example.opsagent.ticket.enums;

import java.util.Locale;
import java.util.Set;

/**
 * 定义工单最小业务闭环的状态和单向流转规则。
 *
 * @author heyu
 * @since 2026/8/16
 */
public enum TicketStatus {
    CREATED(Set.of("PROCESSING")),
    PROCESSING(Set.of("RESOLVED")),
    RESOLVED(Set.of("CLOSED")),
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
            throw new IllegalArgumentException("工单状态只能是 CREATED、PROCESSING、RESOLVED 或 CLOSED");
        }
    }
}
