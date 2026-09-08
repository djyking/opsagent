package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * 简单检查站内通知接收人和重复消息，未向外部渠道发送消息。
 *
 * @author heyu
 * @since 2026/9/3
 */
class SlaNotificationServiceTest {
    @Test
    void selectsPrimaryAndAddsSecondaryOnlyForSecondEscalation() {
        var members =
                List.of(
                        Map.<String, Object>of("roleType", "PRIMARY", "userId", 1L),
                        Map.<String, Object>of("roleType", "PRIMARY", "userId", 1L),
                        Map.<String, Object>of("roleType", "SECONDARY", "userId", 2L));
        assertThat(SlaNotificationService.recipients(members, 0)).containsExactly(1L);
        assertThat(SlaNotificationService.recipients(members, 2)).containsExactly(1L, 2L);
    }

    @Test
    void persistsOneRecipientNotificationAndDoesNotDuplicateConsumedEvent() throws Exception {
        var audit = mock(PlatformAuditRepository.class);
        var oncall = mock(ItsmPlatformRepository.class);
        when(oncall.currentOnCall("redis"))
                .thenReturn(
                        new CurrentOnCallResponse(
                                false, "", List.of(Map.of("roleType", "PRIMARY", "userId", 1L))));
        when(audit.consumeOnce("platform-sla-notification", "test-event")).thenReturn(1, 0);
        var event =
                new ObjectMapper()
                        .readTree(
                                """
{"eventId":"test-event","eventType":"sla.response_warning",
 "payload":{"ticketId":123,"serviceCiCode":"redis","escalationLevel":0}}
""");
        var service = new SlaNotificationService(audit, oncall);
        assertThat(service.record(event)).isTrue();
        assertThat(service.record(event)).isFalse();
        verify(audit, times(1))
                .addNotification(
                        matches("[a-f0-9]{64}"),
                        eq(123L),
                        eq(1L),
                        eq("响应 SLA 即将到期"),
                        contains("redis"));
    }
}
