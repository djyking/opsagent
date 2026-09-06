package com.opsagent.platform;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 以独立内存数据库验证固定演练闭环、失败证据、权限、定时只读巡检及中断记录。
 *
 * @author heyu
 * @since 2026/9/3
 */
class OperationsWorkflowTest {
    private JdbcTemplate jdbc;
    private OperationsWorkflowRepository repository;
    private final OperationsOverviewService overview = mock(OperationsOverviewService.class);
    private final OperationsLabClient lab = mock(OperationsLabClient.class);
    private OperationsWorkflowService service;

    @BeforeEach
    void prepare() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:workflow-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("operations-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        repository = new OperationsWorkflowRepository(jdbc);
        service = new OperationsWorkflowService(repository, overview, lab);
        when(lab.configured()).thenReturn(true);
        authenticate("OPS");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldPersistActualHealthTransitionsAndAuditsForIsolatedWorkflow() {
        when(lab.health()).thenReturn(probe(200), probe(503), probe(200));
        var result = service.start(new OperationsDtos.StartRun("ISOLATED_DRILL", "SERVICE_UNAVAILABLE"));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.steps()).hasSize(5).allMatch(step -> "SUCCEEDED".equals(step.status()));
        assertThat(result.steps().get(2).evidence()).contains("HTTP 503", "ISOLATED_LAB");
        assertThat(result.steps().get(4).evidence()).contains("HTTP 200");
        assertThat(result.audits()).hasSize(7);
        verify(lab).fault();
        verify(lab).recover();
        assertThat(repository.page(1, 10).records()).hasSize(1);
        assertThat(repository.lastRunAt("ISOLATED_DRILL")).isNotNull();
    }

    @Test
    void shouldPreserveExpectedAndActualStatusOnFailedFaultDetectionAndRecover() {
        when(lab.health()).thenReturn(probe(200), probe(200));
        var result = service.start(new OperationsDtos.StartRun("ISOLATED_DRILL", "SERVICE_UNAVAILABLE"));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps().get(2).status()).isEqualTo("FAILED");
        assertThat(result.steps().get(2).evidence()).contains("UNEXPECTED_HEALTH", "期望HTTP 503", "HTTP 200");
        assertThat(result.audits()).anyMatch(audit -> "CLEANUP".equals(audit.action()));
        verify(lab).recover();
    }

    @Test
    void shouldNotInjectWhenBaselineAlreadyFails() {
        when(lab.health()).thenReturn(probe(503));
        var result = service.start(new OperationsDtos.StartRun("ISOLATED_DRILL", "SERVICE_UNAVAILABLE"));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.steps().get(0).evidence()).contains("期望HTTP 200", "HTTP 503");
        verify(lab, never()).fault();
        verify(lab, never()).recover();
    }

    @Test
    void shouldDenyReadOnlyAndDemoUsersBeforeCreatingRun() {
        for (String role : List.of("USER", "DEMO")) {
            authenticate(role);
            assertThatThrownBy(() -> service.start(new OperationsDtos.StartRun("HEALTH_CHECK", null)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("管理员或运维");
        }
        assertThat(repository.page(1, 10).total()).isZero();
        verify(lab, never()).fault();
    }

    @Test
    void shouldScheduleOnlyReadOnlyInspectionAndKeepUnknownAsAttention() {
        SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(service, "inspectionEnabled", true);
        when(overview.inspection()).thenReturn(new OperationsDtos.Overview(Instant.now(), "Prometheus", "UNKNOWN",
                "没有足够样本", 60, List.of(), List.of(), List.of(), null, null));
        when(overview.targets()).thenReturn(List.of());
        service.scheduledInspection();
        var runs = repository.page(1, 10);
        assertThat(runs.records()).hasSize(1);
        var run = repository.detail(runs.records().get(0).id());
        assertThat(run.workflowCode()).isEqualTo("HEALTH_CHECK");
        assertThat(run.actor()).isEqualTo("system-health");
        assertThat(run.status()).isEqualTo("ATTENTION");
        assertThat(run.steps()).hasSize(3);
        verify(lab, never()).fault();
        verify(lab, never()).recover();
    }

    @Test
    void shouldLeaveScheduleDisabledByDefaultAndMarkInterruptedRunsWithoutReplay() {
        service.scheduledInspection();
        assertThat(repository.page(1, 10).total()).isZero();
        var workflow = service.workflows().get(0);
        long id = repository.start(workflow, new OpsPrincipal(1, "operator", "test", List.of("OPS")));
        repository.startStep(id, 1, "真实采集");
        service.recoverInterruptedRuns();
        var interrupted = repository.detail(id);
        assertThat(interrupted.status()).isEqualTo("FAILED");
        assertThat(interrupted.steps().get(0).status()).isEqualTo("FAILED");
        assertThat(interrupted.audits()).anyMatch(audit -> "INTERRUPTED".equals(audit.action()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operations_workflow_run", Integer.class)).isEqualTo(1);
    }

    @Test
    void shouldPreserveMillisecondPrecisionForFastRunAndStepCompletion() {
        for (int i = 0; i < 5; i++) {
            long id = repository.start(service.workflows().get(0),
                    new OpsPrincipal(1, "operator", "test", List.of("OPS")));
            repository.startStep(id, 1, "快速只读检查");
            repository.finishStep(id, 1, "SUCCEEDED", "完成", "已确认");
            repository.finish(id, "SUCCEEDED", "完成");
            var run = repository.detail(id);
            assertThat(run.finishedAt()).isAfterOrEqualTo(run.startedAt());
            assertThat(run.steps().get(0).finishedAt()).isAfterOrEqualTo(run.steps().get(0).startedAt());
        }
    }

    private OperationsLabClient.Probe probe(int status) {
        return new OperationsLabClient.Probe(status, status == 200 ? "UP" : "DOWN", Instant.now());
    }

    private void authenticate(String role) {
        var principal = new OpsPrincipal(1, "test-operator", "test", List.of(role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
