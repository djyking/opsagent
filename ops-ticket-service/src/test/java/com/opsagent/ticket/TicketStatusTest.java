package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 工单状态流转规则测试。
 *
 * @author heyu
 * @since 2026/8/15
 */
class TicketStatusTest {
    @Test
    void supportsMainLifecycleAndRejectsIllegalJumps() {
        assertThat(TicketStatus.CREATED.allows(TicketStatus.ASSIGNED)).isTrue();
        assertThat(TicketStatus.ASSIGNED.allows(TicketStatus.PROCESSING)).isTrue();
        assertThat(TicketStatus.PROCESSING.allows(TicketStatus.RESOLVED)).isTrue();
        assertThat(TicketStatus.RESOLVED.allows(TicketStatus.CLOSED)).isTrue();
        assertThat(TicketStatus.CREATED.allows(TicketStatus.CLOSED)).isFalse();
        assertThat(TicketStatus.CLOSED.allows(TicketStatus.PROCESSING)).isFalse();
    }

    @Test
    void supportsSuspendResumeAndConfirmationBranches() {
        assertThat(TicketStatus.PROCESSING.allows(TicketStatus.SUSPENDED)).isTrue();
        assertThat(TicketStatus.SUSPENDED.allows(TicketStatus.PROCESSING)).isTrue();
        assertThat(TicketStatus.PROCESSING.allows(TicketStatus.WAITING_CONFIRM)).isTrue();
        assertThat(TicketStatus.WAITING_CONFIRM.allows(TicketStatus.RESOLVED)).isTrue();
    }
}
