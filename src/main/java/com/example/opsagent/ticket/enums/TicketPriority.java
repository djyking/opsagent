package com.example.opsagent.ticket.enums;

import java.util.Locale;

/**
 * 工单优先级及其输入校验规则。
 *
 * @author heyu
 * @since 2026/8/15
 */
public enum TicketPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT;

    public static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("工单优先级不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("工单优先级只能是 LOW、MEDIUM、HIGH 或 URGENT");
        }
    }
}
