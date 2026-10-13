package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 以真实 SQL 与两个执行器验证时隙幂等、租约隔离、恢复、未知结果及有界超时。
 *
 * @author heyu
 * @since 2026/9/3
 */
class InspectionExecutionTest {
    private JdbcTemplate jdbc;
    private InspectionExecutionRepository repository;
    private ObservabilityInspectionService service;
    private final TopologyAggregationService topology = mock(TopologyAggregationService.class);
    private final ItsmPlatformService cmdb = mock(ItsmPlatformService.class);
    private static final Map<String, Object> TARGET =
            Map.of("ciCode", "service-a", "environment", "PROD");

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:inspection-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(
                        new ClassPathResource("observability-schema.sql"),
                        new ClassPathResource("operations-schema.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        repository =
                new InspectionExecutionRepository(
                        jdbc, new ObjectMapper().findAndRegisterModules());
        service =
                new ObservabilityInspectionService(
                        topology, mock(ObservabilityRepository.class), cmdb, repository);
        when(cmdb.ci("service-a")).thenReturn(TARGET);
        when(cmdb.cis(null, null)).thenReturn(List.of(TARGET));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(1, "operator", "test", List.of("OPS")),
                                null,
                                List.of()));
    }

    @AfterEach
    void close() {
        service.close();
        SecurityContextHolder.clearContext();
    }

    @Test
    void twoProcessesOnlyClaimOneScheduledSlotAndNextSlotStillExecutes() throws Exception {
        var peer =
                new InspectionExecutionRepository(
                        jdbc, new ObjectMapper().findAndRegisterModules());
        Instant now = Instant.now();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return reserve(repository, "SCHEDULED", true, now);
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return reserve(peer, "SCHEDULED", true, now);
                            });
            start.countDown();
            var left = first.get(5, TimeUnit.SECONDS);
            var right = second.get(5, TimeUnit.SECONDS);
            assertThat(left == null ^ right == null).isTrue();
            var winner = left == null ? right : left;
            repository.complete(winner, node("UNKNOWN"), now.plusMillis(10));
            repository.finish(winner, "COMPLETED", "TARGET_MISSING", now.plusMillis(10));
            assertThat(repository.history("service-a"))
                    .singleElement()
                    .satisfies(
                            row -> {
                                assertThat(row.get("executionStatus")).isEqualTo("COMPLETED");
                                assertThat(row.get("result")).isEqualTo("UNKNOWN");
                                assertThat(row.get("status")).isEqualTo("UNKNOWN");
                                assertThat(row.get("runId")).isNotNull();
                                assertThat(row.get("evidenceRefs")).isNotNull();
                            });
            var next = reserve(peer, "SCHEDULED", true, winner.nextRunAt().plusMillis(10));
            assertThat(next.acquired()).isTrue();
            assertThat(next.runId()).isNotEqualTo(winner.runId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manualBusyDisabledAndMissedSlotsLeaveExplicitEvidence() {
        Instant now = Instant.now();
        var manual = reserve(repository, "MANUAL", true, now);
        var busy = reserve(repository, "SCHEDULED", true, now);
        assertThat(busy.acquired()).isFalse();
        assertThat(repository.run(busy.runId()).get(0))
                .containsEntry("reasonCode", "EXECUTOR_BUSY")
                .containsEntry("executionStatus", "SKIPPED")
                .containsEntry("result", "UNKNOWN");
        repository.finish(manual, "FAILED", "CAPTURE_FAILED", now.plusMillis(10));
        var disabled = reserve(repository, "SCHEDULED", false, busy.nextRunAt().plusMillis(10));
        assertThat(repository.run(disabled.runId()).get(0))
                .containsEntry("reasonCode", "PLAN_DISABLED")
                .containsEntry("nextRunAt", null);
        var late = reserve(repository, "SCHEDULED", true, now.plusSeconds(601));
        assertThat(late.acquired()).isTrue();
        assertThat(repository.history("service-a"))
                .anySatisfy(
                        row -> {
                            assertThat(row.get("reasonCode")).isEqualTo("MISSED_SCHEDULE");
                            assertThat(row.get("executionStatus")).isEqualTo("SKIPPED");
                            assertThat(((Map<?, ?>) row.get("evidence")).get("missedSlots"))
                                    .isNotNull();
                        });
    }

    @Test
    void mysqlLocalDateTimeMapValuesPreserveDriverTimeZoneForPlanLeaseAndSchedule() {
        var mysqlJdbc = mysqlDatetimeJdbc();
        var mysqlRepository =
                new InspectionExecutionRepository(
                        mysqlJdbc, new ObjectMapper().findAndRegisterModules());
        Instant at = Instant.parse("2026-09-03T12:34:56.123Z");
        Instant slot = Instant.parse("2026-09-03T12:34:00Z");
        assertThat(mysqlRepository.schedule(true, 60000)).containsEntry("nextRunAt", null);

        var manual = reserve(mysqlRepository, "MANUAL", true, at);
        assertThat(manual.acquired()).isTrue();
        assertThat(manual.scheduledFor()).isEqualTo(at);
        assertThat(manual.nextRunAt()).isEqualTo(slot);
        // Reproduce Connector/J's generic DATETIME result, including the non-null lease field.
        var generic = mysqlJdbc.queryForMap("SELECT * FROM observability_inspection_plan");
        assertThat(generic.get("next_run_at"))
                .isInstanceOf(LocalDateTime.class)
                .isEqualTo(Timestamp.from(slot).toLocalDateTime());
        assertThat(generic.get("lease_until"))
                .isInstanceOf(LocalDateTime.class)
                .isEqualTo(Timestamp.from(at.plusSeconds(120)).toLocalDateTime());
        assertThat(mysqlRepository.schedule(true, 60000))
                .containsEntry("nextRunAt", slot.toString());
        assertThat(mysqlRepository.run(manual.runId()).get(0))
                .containsEntry("scheduledFor", at.toString());

        var busy = reserve(mysqlRepository, "SCHEDULED", true, at.plusSeconds(1));
        assertThat(busy.acquired()).isFalse();
        assertThat(mysqlRepository.run(busy.runId()).get(0))
                .containsEntry("reasonCode", "EXECUTOR_BUSY");
        assertThat(mysqlRepository.schedule(true, 60000))
                .containsEntry("nextRunAt", slot.plusSeconds(60).toString());

        mysqlRepository.finish(manual, "FAILED", "CAPTURE_FAILED", at.plusSeconds(2));
        var next = reserve(mysqlRepository, "SCHEDULED", true, slot.plusSeconds(60));
        assertThat(next.acquired()).isTrue();
        assertThat(next.scheduledFor()).isEqualTo(slot.plusSeconds(60));
        assertThat(next.nextRunAt()).isEqualTo(slot.plusSeconds(120));
        assertThat(mysqlRepository.schedule(false, 60000)).containsEntry("nextRunAt", null);
    }

    @Test
    void expiredLeaseRecoversAndOldExecutorCannotOverwriteOrReleaseNewRun() {
        Instant now = Instant.now();
        var abandoned = reserve(repository, "MANUAL", true, now);
        var peer = new InspectionExecutionRepository(jdbc, new ObjectMapper());
        Instant later = now.plusSeconds(121);
        var replacement = reserve(peer, "MANUAL", true, later);
        assertThat(replacement.acquired()).isTrue();
        repository.complete(abandoned, node("HEALTHY"), later);
        repository.finish(abandoned, "COMPLETED", "TARGET_MISSING", later);
        assertThat(repository.run(abandoned.runId()).get(0))
                .containsEntry("executionStatus", "TIMED_OUT")
                .containsEntry("result", "UNKNOWN")
                .containsEntry("reasonCode", "LEASE_EXPIRED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lease_owner FROM observability_inspection_plan",
                                String.class))
                .isEqualTo(replacement.runId());
    }

    @Test
    void expiredInspectionWorkflowIsInterruptedOnceWhileOtherInstanceRunIsPreserved() {
        var workflows = new OperationsWorkflowRepository(jdbc);
        var serviceWorkflows =
                new OperationsWorkflowService(
                        workflows,
                        mock(OperationsOverviewService.class),
                        mock(OperationsLabClient.class));
        var actor = new OpsPrincipal(1, "operator", "test", List.of("OPS"));
        long abandonedWorkflow = workflows.start(serviceWorkflows.workflows().get(0), actor);
        long activeWorkflow = workflows.start(serviceWorkflows.workflows().get(0), actor);
        Instant now = Instant.now();
        var abandoned = reserve(repository, "MANUAL", true, now);
        repository.bindWorkflow(abandoned, abandonedWorkflow);
        workflows.startStep(abandonedWorkflow, 1, "读取指标");
        repository.recoverExpired(now.plusSeconds(121));
        workflows.interruptExpiredInspections(repository.expiredWorkflows());
        workflows.interruptExpiredInspections(repository.expiredWorkflows());
        assertThat(workflows.detail(abandonedWorkflow).status()).isEqualTo("FAILED");
        assertThat(workflows.detail(abandonedWorkflow).audits()).hasSize(1);
        assertThat(workflows.detail(activeWorkflow).status()).isEqualTo("RUNNING");
    }

    @Test
    void completedDegradationUsesWarningConsistentlyInHistoryAndEnvironmentCounters() {
        Instant now = Instant.now();
        var degraded = reserve(repository, "MANUAL", true, now);
        repository.complete(degraded, node("DEGRADED"), now.plusMillis(10));
        repository.finish(degraded, "COMPLETED", "TARGET_MISSING", now.plusMillis(10));
        assertThat(repository.run(degraded.runId()).get(0))
                .containsEntry("status", "WARNING")
                .containsEntry("result", "ABNORMAL");
        assertThat(repository.today("PROD")).containsEntry("WARNING", 1L);
        assertThat(repository.today("DEMO")).containsEntry("total", 0L);
    }

    @Test
    void manualCapturePersistsUnknownSeparatelyFromCompletedAndOriginalSampleTime() {
        when(topology.topology("ALL", "15m", "CONFIGURED"))
                .thenReturn(Map.of("nodes", List.of(node("UNKNOWN"))));
        var row = service.run("service-a");
        assertThat(row)
                .containsEntry("executionStatus", "COMPLETED")
                .containsEntry("result", "UNKNOWN")
                .containsEntry("source", "MANUAL")
                .containsEntry("reasonCode", "OBSERVATION_INSUFFICIENT");
        assertThat(((Map<?, ?>) row.get("evidence")).get("observedAt"))
                .isEqualTo("2026-09-03T00:00:00Z");
        assertThat(row.get("startedAt")).isNotNull();
        assertThat(row.get("finishedAt")).isNotNull();
    }

    @Test
    void timeoutAndAdapterFailureAreDurableAndNeverPass() {
        ReflectionTestUtils.setField(service, "timeoutMs", 1000L);
        when(topology.topology(any(), any(), any()))
                .thenAnswer(
                        call -> {
                            Thread.sleep(10000);
                            return Map.of("nodes", List.of(node("HEALTHY")));
                        });
        var timedOut = service.run("service-a");
        assertThat(timedOut)
                .containsEntry("executionStatus", "TIMED_OUT")
                .containsEntry("reasonCode", "CAPTURE_TIMEOUT")
                .containsEntry("result", "UNKNOWN");
        service.close();
        service =
                new ObservabilityInspectionService(
                        topology, mock(ObservabilityRepository.class), cmdb, repository);
        doThrow(new IllegalStateException("source unavailable"))
                .when(topology)
                .topology(any(), any(), any());
        assertThat(service.run("service-a"))
                .containsEntry("executionStatus", "FAILED")
                .containsEntry("reasonCode", "CAPTURE_FAILED")
                .containsEntry("result", "UNKNOWN");
    }

    @Test
    void existingWorkflowSchedulerPersistsOneManualAndTwoAutomaticRunsWithoutSecondScheduler() {
        var workflowRepository = new OperationsWorkflowRepository(jdbc);
        var overview = mock(OperationsOverviewService.class);
        var workflows =
                new OperationsWorkflowService(
                        workflowRepository, overview, mock(OperationsLabClient.class));
        ReflectionTestUtils.setField(workflows, "observationInspections", service);
        ReflectionTestUtils.setField(workflows, "inspectionEnabled", true);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "intervalMs", 60000L);
        when(topology.topology("ALL", "15m", "CONFIGURED"))
                .thenReturn(Map.of("nodes", List.of(node("UNKNOWN"))));
        when(overview.inspection())
                .thenReturn(
                        new OperationsDtos.Overview(
                                Instant.now(),
                                "Prometheus",
                                "UNKNOWN",
                                "无足够业务证据",
                                60,
                                List.of(),
                                List.of(),
                                List.of(),
                                null,
                                null));
        when(overview.targets()).thenReturn(List.of());
        service.run("service-a");
        workflows.scheduledInspection();
        jdbc.update(
                "UPDATE observability_inspection_plan SET next_run_at=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        workflows.scheduledInspection();
        var rows = repository.history("service-a");
        assertThat(rows)
                .hasSize(3)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("runId")).isNotNull();
                            assertThat(row.get("executionStatus")).isEqualTo("COMPLETED");
                            assertThat(row.get("result")).isEqualTo("UNKNOWN");
                            assertThat(row.get("evidenceRefs")).isNotNull();
                        });
        assertThat(rows.stream().map(row -> row.get("runId")).distinct()).hasSize(3);
        assertThat(rows.stream().filter(row -> "SCHEDULED".equals(row.get("source"))))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("workflowRunId")).isNotNull());
        assertThat(workflowRepository.page(1, 10).records()).hasSize(2);
    }

    private InspectionExecutionRepository.Reservation reserve(
            InspectionExecutionRepository target, String source, boolean enabled, Instant now) {
        return target.reserve(
                List.of(TARGET), 1, "test-executor", source, enabled, 60000, 120000, now);
    }

    private JdbcTemplate mysqlDatetimeJdbc() {
        return new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            protected RowMapper<Map<String, Object>> getColumnMapRowMapper() {
                return new ColumnMapRowMapper() {
                    @Override
                    protected Object getColumnValue(ResultSet row, int index) throws SQLException {
                        Object value = super.getColumnValue(row, index);
                        return value instanceof Timestamp timestamp
                                ? timestamp.toLocalDateTime()
                                : value;
                    }
                };
            }
        };
    }

    private Map<String, Object> node(String health) {
        return Map.of(
                "ciCode",
                "service-a",
                "environment",
                "PROD",
                "health",
                health,
                "statusReason",
                "已接入观测范围",
                "metrics",
                Map.of(),
                "observedAt",
                "2026-09-03T00:00:00Z");
    }
}
