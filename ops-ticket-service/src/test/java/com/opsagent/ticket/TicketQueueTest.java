package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * @author heyu
 * @since 2026/9/3
 */
class TicketQueueTest {
    @Test
    void serializesExistingTicketFieldsAlongsideIndependentEventStage() throws Exception {
        var ticket =
                new TicketDtos.View(
                        42L,
                        "T42",
                        "验证中的事件",
                        "处理完成",
                        "HIGH",
                        "RESOLVED",
                        "E42",
                        "VERIFYING",
                        false,
                        false,
                        1L,
                        1L,
                        "mysql",
                        "MANUAL",
                        "CORE",
                        null,
                        null,
                        null,
                        false,
                        3,
                        null,
                        null);
        var row =
                new TicketQueueDtos.Row(
                        ticket,
                        "READY_TO_CLOSE",
                        "admin",
                        TicketQueueService.brief(null, LocalDateTime.now()));
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(row));
        assertThat(json.path("id").asLong()).isEqualTo(42);
        assertThat(json.has("ticket")).isFalse();
        assertThat(json.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(json.path("currentStage").asText()).isEqualTo("READY_TO_CLOSE");
    }

    @Test
    void slaDistinguishesMissingCompletedPausedAndOverdue() {
        var now = LocalDateTime.of(2026, 9, 3, 12, 0);
        assertThat(TicketQueueService.brief(null, now).applicable()).isFalse();
        var base =
                Map.<String, Object>of(
                        "responseStatus",
                        "MET",
                        "resolutionStatus",
                        "RUNNING",
                        "responseDeadline",
                        now.minusHours(2),
                        "resolutionDeadline",
                        now.minusSeconds(1));
        assertThat(TicketQueueService.brief(base, now).breached()).isTrue();
        assertThat(
                        TicketQueueService.brief(
                                        Map.of(
                                                "responseStatus",
                                                "MET",
                                                "resolutionStatus",
                                                "MET",
                                                "resolutionDeadline",
                                                now.minusHours(1)),
                                        now)
                                .breached())
                .isFalse();
        var paused =
                TicketQueueService.brief(
                        Map.of(
                                "responseStatus",
                                "MET",
                                "resolutionStatus",
                                "PAUSED",
                                "resolutionDeadline",
                                now.minusHours(1)),
                        now);
        assertThat(paused.paused()).isTrue();
        assertThat(paused.breached()).isFalse();
    }
}
