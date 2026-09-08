package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Small safety checks for recovery reassociation and stale confirmation invalidation.
 *
 * @author heyu
 * @since 2026/9/3
 */
class EventRecoveryBindingTest {
    private final TicketService ticketService = mock(TicketService.class);
    private final TicketMapper tickets = mock(TicketMapper.class);
    private final TicketAuditMapper audit = mock(TicketAuditMapper.class);
    private final EventRecoveryVerifier verifier = mock(EventRecoveryVerifier.class);
    private final Ticket ticket = new Ticket();
    private final List<TicketAuditMapper.WorkRecord> records = new ArrayList<>();
    private final List<TicketAuditMapper.Operation> operations = new ArrayList<>();
    private final EventRecoveryBindingService service =
            new EventRecoveryBindingService(
                    ticketService,
                    tickets,
                    audit,
                    new EventRecoveryRules("CORE=PROD,PROD=PROD", "mysql,redis", 180, 5, 30, 90),
                    verifier,
                    new ObjectMapper());

    @BeforeEach
    void prepare() {
        ticket.setId(1L);
        ticket.setVersion(3);
        ticket.setStatus("RESOLVED");
        ticket.setEnvironment("CORE");
        ticket.setAssigneeId(7L);
        ticket.setCreatorId(8L);
        when(ticketService.require(1)).thenReturn(ticket);
        when(tickets.lock(1)).thenReturn(ticket);
        when(tickets.bindRecovery(eq(1L), anyInt(), anyString(), anyString())).thenReturn(1);
        when(audit.workRecords(1)).thenAnswer(call -> List.copyOf(records));
        when(audit.operations(1)).thenAnswer(call -> List.copyOf(operations));
        when(audit.workRecord(eq(1L), anyString(), anyString(), nullable(String.class), anyLong()))
                .thenAnswer(
                        call -> {
                            records.add(
                                    new TicketAuditMapper.WorkRecord(
                                            records.size() + 1,
                                            1,
                                            call.getArgument(1),
                                            call.getArgument(2),
                                            call.getArgument(3),
                                            call.getArgument(4),
                                            LocalDateTime.now()));
                            return 1;
                        });
        when(audit.operation(eq(1L), anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            operations.add(
                                    new TicketAuditMapper.Operation(
                                            operations.size() + 1,
                                            1,
                                            call.getArgument(1),
                                            call.getArgument(2),
                                            call.getArgument(3),
                                            call.getArgument(4),
                                            LocalDateTime.now()));
                            return 1;
                        });
        actor(7, "ADMIN");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void actor(long id, String role) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(id, "actor", "binding-test", List.of(role)),
                                null,
                                List.of()));
    }

    private EventRecoveryBindingService.Binding request() {
        return new EventRecoveryBindingService.Binding(
                "redis", "CORE", 3, "same-request", "经核对当前故障来自 Redis");
    }

    @Test
    void completionInvalidatesOldConfirmationsAndExactRetryWritesOnce() {
        for (String type : List.of("EVENT_RESULT", "EVENT_TECH_PASS", "EVENT_BUSINESS_CONFIRM"))
            records.add(
                    new TicketAuditMapper.WorkRecord(
                            records.size() + 1, 1, type, "原确认", "原证据", 7, LocalDateTime.now()));
        assertThat(EventLifecycleService.reduce(records).business()).isNotNull();
        var result = service.update(1, request());
        assertThat(result.targetCode()).isEqualTo("redis");
        assertThat(result.version()).isEqualTo(4);
        assertThat(EventLifecycleService.reduce(records).result()).isNull();
        assertThat(EventLifecycleService.reduce(records).technical()).isNull();
        assertThat(EventLifecycleService.reduce(records).business()).isNull();
        assertThat(records).hasSize(4);
        service.update(1, request());
        verify(tickets).bindRecovery(1, 3, "redis", "CORE");
        verify(verifier).validateBindingTarget("redis", "PROD");
        assertThat(operations).hasSize(1);
    }

    @Test
    void userAndDifferentAssigneeCannotBindAndVersionConflictNeverTouchesRegistration() {
        actor(8, "USER");
        assertThat(service.read(1).canEdit()).isFalse();
        assertThatThrownBy(() -> service.update(1, request())).hasMessageContaining("仅管理员");
        actor(9, "OPS");
        assertThatThrownBy(() -> service.update(1, request())).hasMessageContaining("仅管理员");
        actor(7, "OPS");
        ticket.setVersion(4);
        assertThatThrownBy(() -> service.update(1, request())).hasMessageContaining("已被更新");
        verifyNoInteractions(verifier);
        verify(tickets, never()).bindRecovery(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void isolatedSourceOrIncidentCannotBeDowngradedByChangingEnvironment() {
        ticket.setSourceType("ISOLATED_DRILL");
        assertThat(service.read(1).kind()).isEqualTo("ISOLATED");
        assertThat(service.read(1).canEdit()).isFalse();
        assertThatThrownBy(() -> service.update(1, request())).hasMessageContaining("原 incident");
        ticket.setSourceType("ALERT");
        ticket.setIncidentId("original-incident");
        assertThatThrownBy(() -> service.update(1, request())).hasMessageContaining("原 incident");
        verifyNoInteractions(verifier);
    }

    @Test
    void missingEnvironmentAndTargetAreExplicitAndUnknownRulesFailClosed() {
        ticket.setEnvironment(null);
        assertThat(service.read(1).blockers())
                .anyMatch(text -> text.contains("缺少关联服务"))
                .anyMatch(text -> text.contains("缺少事件环境"));
        assertThatThrownBy(
                        () ->
                                service.update(
                                        1,
                                        new EventRecoveryBindingService.Binding(
                                                "redis", "UNKNOWN", 3, "unknown", "需要补全")))
                .hasMessageContaining("尚未绑定");
        verifyNoInteractions(verifier);
    }

    @Test
    void pendingAlertBindingShowsTheSpecificCauseInsteadOfClaimingRulesAreMissing() {
        records.add(
                new TicketAuditMapper.WorkRecord(
                        1,
                        1,
                        "ALERT_BINDING_PENDING",
                        "告警环境与 CMDB 服务环境不一致",
                        "ENVIRONMENT_MISMATCH",
                        0,
                        LocalDateTime.now()));
        assertThat(service.read(1).blockers())
                .contains("告警环境与 CMDB 服务环境不一致")
                .noneMatch(value -> value.contains("缺少关联服务"));
        ticket.setAffectedCiCode("redis");
        assertThat(service.read(1).blockers()).isEmpty();
    }

    @Test
    void registrationFailureAndArchivedStatePreserveAllHistory() {
        doThrow(new IllegalStateException("服务登记不符"))
                .when(verifier)
                .validateBindingTarget("redis", "PROD");
        assertThatThrownBy(() -> service.update(1, request())).hasMessageContaining("服务登记不符");
        verify(tickets, never()).bindRecovery(anyLong(), anyInt(), anyString(), anyString());
        assertThat(records).isEmpty();
        ticket.setStatus("CLOSED");
        assertThat(service.read(1).canEdit()).isFalse();
        assertThatThrownBy(() -> service.update(1, request())).hasMessageContaining("已关闭");
    }
}
