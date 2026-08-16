package com.example.opsagent.ticket.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证工单状态标准化和状态机流转规则。
 *
 * @author heyu
 * @since 2026/8/15
 */
class TicketStatusTest {

    @Test
    void shouldAllowOnlyDefinedTransitions() {
        assertThat(TicketStatus.CREATED.canTransitionTo(TicketStatus.PROCESSING)).isTrue();
        assertThat(TicketStatus.PROCESSING.canTransitionTo(TicketStatus.RESOLVED)).isTrue();
        assertThat(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.PROCESSING)).isFalse();
        assertThat(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.CLOSED)).isTrue();
        assertThat(TicketStatus.CREATED.canTransitionTo(TicketStatus.CLOSED)).isFalse();
        assertThat(TicketStatus.CLOSED.canTransitionTo(TicketStatus.CREATED)).isFalse();
    }

    @Test
    void shouldNormalizeInputAndRejectUnknownStatus() {
        assertThat(TicketStatus.parse(" processing ")).isEqualTo(TicketStatus.PROCESSING);
        assertThatThrownBy(() -> TicketStatus.parse("unknown"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
