package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real isolated SQL proves recovery watches keep source evidence and handling-result boundaries.
 *
 * @author heyu
 * @since 2026/9/3
 */
class EventRecoveryObservationServiceTest {
    private final TopologyAggregationService topology = mock(TopologyAggregationService.class);
    private JdbcTemplate jdbc;
    private EventRecoveryObservationService service;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final Instant handled = now.minusSeconds(300);

    @BeforeEach
    void setup() {
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:recovery-"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                "CREATE TABLE observability_recovery_watch(ticket_id BIGINT PRIMARY KEY,ci_code"
                    + " VARCHAR(64),environment VARCHAR(32),result_at TIMESTAMP(3),actor_id"
                    + " BIGINT,expires_at TIMESTAMP(3),active BOOLEAN,updated_at TIMESTAMP DEFAULT"
                    + " CURRENT_TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE observability_recovery_sample(id BIGINT AUTO_INCREMENT PRIMARY"
                    + " KEY,ticket_id BIGINT,ci_code VARCHAR(64),environment VARCHAR(32),result_at"
                    + " TIMESTAMP(3),checked_at TIMESTAMP(3),result VARCHAR(16),evidence_json"
                    + " CLOB)");
        service = new EventRecoveryObservationService(jdbc, new ObjectMapper(), topology, 3600, 30);
        when(topology.recoveryNode("mysql")).thenReturn(node());
    }

    @Test
    void oneCurrentCheckIsPersistedWithOriginalMetricTimeAndCannotBeRepeatedRapidly() {
        service.start(9, "mysql", "PROD", handled, 1);
        service.capture(watch(), now);
        service.capture(watch(), now.plusSeconds(1));
        var history = service.history(9, "mysql", "PROD");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("result")).isEqualTo("PASS");
        var evidence = (Map<?, ?>) history.get(0).get("evidence");
        assertThat(evidence.get("observedAt")).isEqualTo(now.minusSeconds(10).toString());
        assertThat(evidence.get("metricEvidence")).isNotNull();
    }

    @Test
    void newHandlingResultHasIndependentHistoryAndOldRequestCannotReplaceIt() {
        service.start(9, "mysql", "PROD", handled, 1);
        service.capture(watch(), now);
        service.start(9, "mysql", "PROD", now.minusSeconds(5), 1);
        assertThat(service.history(9, "mysql", "PROD")).isEmpty();
        assertThatThrownBy(() -> service.start(9, "mysql", "PROD", handled, 1))
                .hasMessageContaining("旧恢复观察");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM observability_recovery_sample", Long.class))
                .isEqualTo(1);
    }

    @Test
    void mysqlDatetimeIsReadThroughTypedTimestampEvenWhenGetObjectReturnsLocalDateTime()
            throws Exception {
        JdbcTemplate mysqlJdbc = mock(JdbcTemplate.class);
        ResultSet mysqlRow = mock(ResultSet.class);
        when(mysqlRow.getObject("result_at"))
                .thenReturn(LocalDateTime.ofInstant(handled, ZoneId.systemDefault()));
        when(mysqlRow.getTimestamp("result_at")).thenReturn(Timestamp.from(handled));
        doAnswer(
                        invocation -> {
                            RowMapper<?> mapper = invocation.getArgument(1);
                            return List.of(mapper.mapRow(mysqlRow, 0));
                        })
                .when(mysqlJdbc)
                .query(contains("SELECT result_at"), any(RowMapper.class), eq(9L));
        var mysqlService =
                new EventRecoveryObservationService(
                        mysqlJdbc, new ObjectMapper(), topology, 3600, 30);
        mysqlService.start(9, "mysql", "PROD", handled, 1);
        assertThatThrownBy(() -> mysqlService.start(9, "mysql", "PROD", handled.minusSeconds(1), 1))
                .hasMessageContaining("旧恢复观察");
        verify(mysqlRow, times(2)).getTimestamp("result_at");
        verify(mysqlRow, never()).getObject("result_at");
    }

    @Test
    void retryingSameHandlingResultPreservesAlreadyRecordedSamples() {
        service.start(9, "mysql", "PROD", handled, 1);
        service.capture(watch(), now);
        var original = service.history(9, "mysql", "PROD");
        service.start(9, "mysql", "PROD", handled, 1);
        assertThat(service.history(9, "mysql", "PROD")).isEqualTo(original);
    }

    @Test
    void sourceFailureStoresAnUnknownCheckRatherThanReusingLastHealthyValue() {
        service.start(9, "mysql", "PROD", handled, 1);
        when(topology.recoveryNode("mysql")).thenThrow(new IllegalStateException("source failed"));
        service.capture(watch(), now);
        assertThat(service.history(9, "mysql", "PROD").get(0).get("result")).isEqualTo("UNKNOWN");
    }

    @Test
    void anotherEnvironmentCannotStartOrReadThisWatch() {
        assertThatThrownBy(() -> service.start(9, "mysql", "DEMO", handled, 1))
                .hasMessageContaining("环境");
        service.start(9, "mysql", "PROD", handled, 1);
        service.capture(watch(), now);
        assertThat(service.history(9, "mysql", "DEMO")).isEmpty();
        assertThat(service.history(10, "mysql", "PROD")).isEmpty();
    }

    @Test
    void stopRetainsHistoryButDisablesFutureBackgroundSampling() {
        service.start(9, "mysql", "PROD", handled, 1);
        service.capture(watch(), now);
        service.stop(9, "mysql", "PROD");
        service.tick();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT active FROM observability_recovery_watch WHERE ticket_id=9",
                                Boolean.class))
                .isFalse();
        assertThat(service.history(9, "mysql", "PROD")).hasSize(1);
    }

    private Map<String, Object> watch() {
        return Map.of(
                "ticket_id",
                9L,
                "ci_code",
                "mysql",
                "environment",
                "PROD",
                "result_at",
                Timestamp.from(handled));
    }

    private Map<String, Object> node() {
        return Map.of(
                "ciCode",
                "mysql",
                "environment",
                "PROD",
                "health",
                "HEALTHY",
                "observedAt",
                now.minusSeconds(10).toString(),
                "identity",
                Map.of("ciCode", "mysql", "environment", "PROD"),
                "observation",
                Map.of("status", "READY"),
                "metricEvidence",
                Map.of("mysqlQuerySuccess", Map.of("value", 1)),
                "evidenceRefs",
                List.of("mysql-read-check"));
    }
}
