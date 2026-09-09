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
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证事件决议与工单状态分离、独立责任记录、并发和重试边界。
 *
 * @author heyu
 * @since 2026/9/3
 */
class EventLifecycleTest {
    private final TicketService ticketsService = mock(TicketService.class);
    private final TicketMapper tickets = mock(TicketMapper.class);
    private final TicketAuditMapper audit = mock(TicketAuditMapper.class);
    private final AlertEpisodeMapper episodes = mock(AlertEpisodeMapper.class);
    private final EventRecoveryVerifier verifier = mock(EventRecoveryVerifier.class);
    private final List<TicketAuditMapper.WorkRecord> records = new ArrayList<>();
    private final List<TicketAuditMapper.Operation> operations = new ArrayList<>();
    private final Ticket ticket = new Ticket();
    private final EventLifecycleService service =
            new EventLifecycleService(
                    ticketsService, tickets, audit, new ObjectMapper(), episodes, verifier, "");

    @BeforeEach
    void prepare() {
        ticket.setId(1L);
        ticket.setStatus("RESOLVED");
        ticket.setVersion(3);
        ticket.setAssigneeId(7L);
        ticket.setCreatorId(8L);
        ticket.setAffectedCiCode("ops-rag-service");
        when(ticketsService.require(1)).thenReturn(ticket);
        when(tickets.lock(1)).thenReturn(ticket);
        when(audit.workRecords(1)).thenAnswer(call -> List.copyOf(records));
        when(audit.operations(1)).thenAnswer(call -> List.copyOf(operations));
        when(tickets.touch(eq(1L), anyInt())).thenReturn(1);
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
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private void actor(long id, String role) {
        var principal = new OpsPrincipal(id, "actor-" + id, "event-lifecycle-test", List.of(role));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private EventLifecycleService.Action action(String type) {
        return new EventLifecycleService.Action(
                type, ticket.getVersion(), UUID.randomUUID().toString(), "实际检查与处理结论", "监控证据及采样时间");
    }

    private void act(String type) {
        service.act(1, action(type));
    }

    @Test
    void resolvedTicketAndOrdinaryNotesNeverCloseAnEvent() {
        records.add(
                new TicketAuditMapper.WorkRecord(
                        1, 1, "VERIFICATION", "历史已通过", "旧证据", 7, LocalDateTime.now()));
        assertThat(service.read(1).stage()).isEqualTo("HANDLING");
        assertThat(service.read(1).technical()).isNull();
        assertThatThrownBy(() -> act("CLOSE")).hasMessageContaining("不允许");
        ticket.setStatus("CLOSED");
        assertThat(service.read(1).closed()).isNull();
    }

    @Test
    void adminMayConfirmBothRolesButMustCreateIndependentEvidenceRecords() {
        act("RESULT");
        act("TECH_PASS");
        assertThat(service.read(1).allowedActions()).doesNotContain("CLOSE");
        act("BUSINESS_CONFIRM");
        assertThat(service.read(1).allowedActions()).contains("CLOSE");
        act("CLOSE");
        assertThat(service.read(1).stage()).isEqualTo("CLOSED");
        assertThat(service.read(1).technical().id()).isNotEqualTo(service.read(1).business().id());
        assertThat(ticket.getStatus()).isEqualTo("RESOLVED");
        assertThat(records).hasSize(4);
    }

    @Test
    void visitorOwnerCompletesDrillConfirmationWithSameEvidenceGatesAndAuditIdentity() {
        actor(-1, "DEMO");
        ticket.setOwnerActorId(-1L);
        ticket.setSourceType("ISOLATED_DRILL");
        ticket.setEnvironment("ISOLATED");
        ticket.setAffectedCiCode("ops-demo-order-service");
        ticket.setIncidentId("incident-1");
        ticket.setAssigneeId(null);
        assertThat(service.read(1).confirmationScope()).isEqualTo("VISITOR_DRILL");
        act("RESULT");
        act("TECH_PASS");
        assertThat(service.read(1).allowedActions()).doesNotContain("CLOSE");
        act("BUSINESS_CONFIRM");
        act("CLOSE");
        assertThat(service.read(1).stage()).isEqualTo("CLOSED");
        assertThat(records)
                .allSatisfy(
                        record -> {
                            assertThat(record.createBy()).isEqualTo(-1);
                            assertThat(record.content()).startsWith("【访客演练确认】");
                        });
        verify(verifier, times(2)).verify(eq(ticket), any());
    }

    @Test
    void publicOrForeignOrNonIsolatedTicketNeverGrantsVisitorConfirmation() {
        actor(-1, "DEMO");
        ticket.setPublicDemo(true);
        ticket.setOwnerActorId(-2L);
        ticket.setSourceType("ISOLATED_DRILL");
        ticket.setEnvironment("ISOLATED");
        ticket.setAffectedCiCode("ops-demo-order-service");
        ticket.setIncidentId("incident-1");
        assertThat(service.read(1).allowedActions()).isEmpty();
        ticket.setOwnerActorId(-1L);
        ticket.setEnvironment("CORE");
        assertThat(service.read(1).allowedActions()).isEmpty();
        ticket.setEnvironment("ISOLATED");
        ticket.setIncidentId(null);
        assertThat(service.read(1).allowedActions()).isEmpty();
    }

    @Test
    void creatorFeedbackDoesNotGrantFormalBusinessConfirmationOrClosure() {
        act("RESULT");
        act("TECH_PASS");
        clearInvocations(verifier);
        actor(8, "USER");
        assertThat(service.read(1).allowedActions()).isEmpty();
        assertThatThrownBy(() -> act("BUSINESS_CONFIRM")).hasMessageContaining("不允许");
        assertThatThrownBy(() -> act("CLOSE")).hasMessageContaining("不允许");
        ticket.setAssigneeId(8L);
        assertThat(service.read(1).allowedActions()).isEmpty();
        verifyNoInteractions(verifier);
    }

    @Test
    void checksVersionAndEvidenceBeforeAnyWrite() {
        act("RESULT");
        clearInvocations(tickets, audit);
        assertThatThrownBy(
                        () ->
                                service.act(
                                        1,
                                        new EventLifecycleService.Action(
                                                "TECH_PASS", 0, "stale", "恢复", "真实证据")))
                .hasMessageContaining("已被更新");
        assertThatThrownBy(
                        () ->
                                service.act(
                                        1,
                                        new EventLifecycleService.Action(
                                                "TECH_PASS",
                                                ticket.getVersion(),
                                                "missing",
                                                "恢复",
                                                " ")))
                .hasMessageContaining("证据");
        verify(tickets, never()).touch(anyLong(), anyInt());
        verifyNoInteractions(verifier);
    }

    @Test
    void lockAndIdempotencyReadPrecedeVerificationAndExactRetryDoesNotVerifyAgain() {
        act("RESULT");
        var request = action("TECH_PASS");
        clearInvocations(tickets, ticketsService, audit, verifier);

        service.act(1, request);

        var order = inOrder(tickets, ticketsService, audit, verifier);
        order.verify(tickets).lock(1);
        order.verify(ticketsService).authorizeWrite(ticket);
        order.verify(audit).operations(1);
        order.verify(audit).workRecords(1);
        order.verify(verifier).verify(ticket, records.get(0).createTime());
        order.verify(tickets).touch(1, request.version());
        order.verify(audit)
                .workRecord(1, "EVENT_TECH_PASS", request.content(), request.evidence(), 7);
        order.verify(audit)
                .operation(
                        eq(1L),
                        eq(7L),
                        eq("EVENT_TECH_PASS"),
                        eq(request.requestId()),
                        anyString());
        verify(ticketsService, never()).require(anyLong());
        verify(audit).operations(1);

        service.act(1, request);

        verify(verifier).verify(ticket, records.get(0).createTime());
        verify(audit, times(2)).operations(1);
        assertThat(records.stream().filter(row -> "EVENT_TECH_PASS".equals(row.recordType())))
                .hasSize(1);
    }

    @Test
    void concurrentCommitWhileWaitingForLockIsReturnedAsRetryWithCachedAuditReads() {
        var request = action("TECH_PASS");
        var cachedOperations = new AtomicReference<List<TicketAuditMapper.Operation>>();
        when(audit.operations(1))
                .thenAnswer(
                        call -> {
                            cachedOperations.compareAndSet(null, List.copyOf(operations));
                            return cachedOperations.get();
                        });
        when(tickets.lock(1))
                .thenAnswer(
                        call -> {
                            // 模拟等待行锁期间另一事务完成同一请求，首读缓存此时才应建立。
                            records.add(
                                    new TicketAuditMapper.WorkRecord(
                                            1,
                                            1,
                                            "EVENT_RESULT",
                                            "处理完成",
                                            "处理证据",
                                            7,
                                            LocalDateTime.now()));
                            records.add(
                                    new TicketAuditMapper.WorkRecord(
                                            2,
                                            1,
                                            "EVENT_TECH_PASS",
                                            request.content(),
                                            request.evidence(),
                                            7,
                                            LocalDateTime.now()));
                            operations.add(
                                    new TicketAuditMapper.Operation(
                                            1,
                                            1,
                                            7,
                                            "EVENT_TECH_PASS",
                                            request.requestId(),
                                            new ObjectMapper()
                                                    .writeValueAsString(
                                                            Map.of(
                                                                    "action", request.action(),
                                                                    "content", request.content(),
                                                                    "evidence",
                                                                            request.evidence())),
                                            LocalDateTime.now()));
                            ticket.setVersion(request.version() + 1);
                            return ticket;
                        });
        clearInvocations(tickets, audit, verifier);

        var result = service.act(1, request);

        assertThat(result.technical()).isNotNull();
        assertThat(result.version()).isEqualTo(request.version() + 1);
        assertThat(operations).hasSize(1);
        verify(audit).operations(1);
        verify(tickets, never()).touch(anyLong(), anyInt());
        verify(audit, never())
                .workRecord(anyLong(), anyString(), anyString(), nullable(String.class), anyLong());
        verify(audit, never())
                .operation(anyLong(), anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(verifier);
    }

    @Test
    void exactRetryDoesNotRepeatTheDecisionAndNewResultInvalidatesConfirmations() {
        var request = action("RESULT");
        service.act(1, request);
        service.act(1, request);
        assertThat(records).hasSize(1);
        assertThatThrownBy(
                        () ->
                                service.act(
                                        1,
                                        new EventLifecycleService.Action(
                                                "RESULT",
                                                ticket.getVersion(),
                                                request.requestId(),
                                                "changed",
                                                "证据")))
                .hasMessageContaining("请求标识");
        act("TECH_PASS");
        act("BUSINESS_CONFIRM");
        act("RESULT");
        assertThat(service.read(1).technical()).isNull();
        assertThat(service.read(1).business()).isNull();
        act("TECH_FAIL");
        assertThat(service.read(1).stage()).isEqualTo("HANDLING");
    }

    @Test
    void
            unknownClassificationRequiresBusinessConfirmationButConfiguredTechnicalOnlyServiceDoesNot() {
        assertThat(service.read(1).businessRequired()).isTrue();
        assertThat(service.read(1).businessRule()).contains("未被明确豁免");
        var classified =
                new EventLifecycleService(
                        ticketsService,
                        tickets,
                        audit,
                        new ObjectMapper(),
                        episodes,
                        verifier,
                        "ops-order-service");
        assertThat(classified.read(1).businessRequired()).isTrue();
        ticket.setAffectedCiCode("ops-order-service");
        assertThat(classified.read(1).businessRequired()).isFalse();
    }

    @Test
    void firingEpisodeBlocksTechnicalConfirmationAndClosure() {
        act("RESULT");
        ticket.setEpisodeId("episode-1");
        var firing =
                new AlertEpisodeMapper.Episode(
                        "episode-1",
                        1,
                        "fingerprint",
                        LocalDateTime.now(),
                        LocalDateTime.now(),
                        "firing",
                        1L,
                        null,
                        7,
                        "CORE",
                        null);
        when(episodes.find("episode-1")).thenReturn(firing);
        when(episodes.lock("episode-1")).thenReturn(firing);
        assertThat(service.read(1).allowedActions()).doesNotContain("TECH_PASS", "CLOSE");
        assertThatThrownBy(() -> act("TECH_PASS")).hasMessageContaining("不允许");
    }

    @Test
    void closedTicketCannotOfferUnusableReopenAndLegacyArchiveIsNotVerifiedClosure() {
        act("RESULT");
        act("TECH_PASS");
        act("BUSINESS_CONFIRM");
        act("CLOSE");
        ticket.setStatus("CLOSED");
        assertThat(service.read(1).allowedActions()).doesNotContain("REOPEN");
        records.clear();
        records.add(
                new TicketAuditMapper.WorkRecord(
                        1, 1, "EVENT_LEGACY_ARCHIVE", "历史档案", "未补造证据", 0, LocalDateTime.now()));
        assertThat(service.read(1).stage()).isEqualTo("LEGACY_ARCHIVED");
        assertThat(service.read(1).closed()).isNull();
        assertThat(service.read(1).allowedActions()).isEmpty();
    }

    @Test
    void retryComparesJsonMeaningAcrossDifferentKeyOrder() throws Exception {
        var request = action("RESULT");
        service.act(1, request);
        var original = operations.get(0);
        var parsed = new ObjectMapper().readTree(original.detailJson());
        String reordered =
                new ObjectMapper()
                        .createObjectNode()
                        .put("evidence", parsed.path("evidence").asText())
                        .put("content", parsed.path("content").asText())
                        .put("action", parsed.path("action").asText())
                        .toString();
        operations.set(
                0,
                new TicketAuditMapper.Operation(
                        original.id(),
                        1,
                        original.operatorId(),
                        original.operation(),
                        original.requestId(),
                        reordered,
                        original.createTime()));
        service.act(1, request);
        assertThat(records).hasSize(1);
    }
}
