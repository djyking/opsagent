package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证额度持久性、日界重置、并发释放、滚动频率与故障关闭，不访问外部模型。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AiBudgetGuardTest {
    private JdbcTemplate jdbc;
    private RagProperties rag;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:budget-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        rag = new RagProperties();
    }

    @Test
    void shouldKeepDailyCountAcrossRestartAndResetAtNextUtcDay() {
        AiBudgetGuard first = guard(1, 1, clock);
        first.acquire().close();
        AiBudgetGuard restarted = guard(1, 1, clock);
        assertThatThrownBy(restarted::acquire).isInstanceOf(BusinessException.class).hasMessageContaining("今日");
        assertThatThrownBy(restarted::checkRequestRate)
                .isInstanceOf(BusinessException.class).hasMessageContaining("今日");
        guard(1, 1, Clock.offset(clock, java.time.Duration.ofDays(1))).acquire().close();
    }

    @Test
    void shouldKeepPermitUntilClosedAndReleaseOnlyOnce() {
        AiBudgetGuard guard = guard(1, 10, clock);
        var first = guard.acquire();
        assertThatThrownBy(guard::acquire).isInstanceOf(BusinessException.class).hasMessageContaining("其他问题");
        first.close();
        first.close();
        try (var second = guard.acquire()) {
            assertThatThrownBy(guard::acquire).isInstanceOf(BusinessException.class).hasMessageContaining("其他问题");
        }
    }

    @Test
    void shouldApplyConfiguredMinuteLimitBeforeRetrievalAndGeneration() {
        rag.setRequestsPerMinute(1);
        AiBudgetGuard guard = guard(2, 10, clock);
        guard.checkRequestRate();
        assertThatThrownBy(guard::checkRequestRate).isInstanceOf(BusinessException.class)
                .hasMessageContaining("频繁");
        guard.acquire().close();
        assertThatThrownBy(guard::acquire).isInstanceOf(BusinessException.class).hasMessageContaining("频繁");
    }

    @Test
    void shouldFailClosedIfPersistentBudgetIsUnavailable() {
        AiBudgetGuard guard = guard(1, 10, clock);
        jdbc.execute("DROP TABLE rag_ai_daily_budget");
        assertThatThrownBy(guard::acquire).isInstanceOf(BusinessException.class).hasMessageContaining("无法核实");
        assertThatThrownBy(guard::checkRequestRate).isInstanceOf(BusinessException.class).hasMessageContaining("无法核实");
    }

    @Test
    void shouldLeaveLocalModeUnrestrictedWithoutCreatingQuotaTable() {
        AiBudgetGuard guard = new AiBudgetGuard(jdbc, rag, false, 1, 1, clock);
        guard.initialize();
        for (int i = 0; i < 25; i++) {
            guard.checkRequestRate();
            guard.acquire().close();
        }
    }

    @Test
    void shouldReserveDailyBudgetAtomicallyAcrossInstances() throws Exception {
        var instances = new java.util.ArrayList<AiBudgetGuard>();
        for (int i = 0; i < 8; i++) instances.add(guard(1, 3, clock));
        var workers = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var calls = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (var instance : instances) calls.add(() -> {
                try (var permit = instance.acquire()) { return true; }
                catch (BusinessException exception) { return false; }
            });
            int successful = 0;
            for (var future : workers.invokeAll(calls)) {
                if (future.get(5, java.util.concurrent.TimeUnit.SECONDS)) successful++;
            }
            org.assertj.core.api.Assertions.assertThat(successful).isEqualTo(3);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                    "SELECT SUM(request_count) FROM rag_ai_daily_budget", Integer.class)).isEqualTo(3);
        } finally {
            workers.shutdownNow();
        }
    }

    private AiBudgetGuard guard(int concurrent, int daily, Clock time) {
        AiBudgetGuard guard = new AiBudgetGuard(jdbc, rag, true, concurrent, daily, time);
        guard.initialize();
        return guard;
    }
}
