package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.UUID;

/**
 * 持久证据只保留实际观测，不暴露场景答案，不把采集时间当成变更时间。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DemoTargetEvidenceTest {
    @Test
    void recordsActualBeforeAfterOnceAndPreservesExactApplicationAndObservationTimes() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:evidence-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("demo-target-schema.sql"))
                .execute(source);
        var json = new ObjectMapper().findAndRegisterModules();
        var repository = new DemoTargetEvidenceRepository(new JdbcTemplate(source), json);
        Instant applied =
                Instant.now().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant observed = applied.plusSeconds(2);
        String id = UUID.randomUUID().toString();
        var snapshot =
                json.createObjectNode()
                        .put("targetCode", DemoTargetDtos.TARGET)
                        .put("incidentId", id)
                        .put("scenarioCode", "NACOS_REDIS_CONFIG_DRIFT")
                        .put("appliedRevision", "b".repeat(64))
                        .put("recoverySource", "")
                        .put("configurationAppliedAt", applied.toString());
        snapshot.putObject("configuration").put("redisPort", 6380);
        snapshot.putObject("previousConfiguration").put("redisPort", 6379);
        snapshot.putObject("business")
                .put("httpStatus", 503)
                .put("reasonCode", "REDIS_CONNECT_FAILED");
        repository.capture(DemoTargetDtos.TARGET, snapshot, observed);
        repository.capture(DemoTargetDtos.TARGET, snapshot, observed.plusSeconds(1));
        var incident =
                new DemoTargetDtos.Incident(
                        id,
                        DemoTargetDtos.TARGET,
                        "NACOS_REDIS_CONFIG_DRIFT",
                        1,
                        "owner",
                        "USER",
                        "FAULT_ACTIVE",
                        "b".repeat(64),
                        applied.minusSeconds(1),
                        applied.plusSeconds(600),
                        null,
                        "",
                        503,
                        "REDIS_CONNECT_FAILED",
                        observed);
        var changes = repository.changes(incident);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0))
                .containsEntry("occurredAt", applied)
                .containsEntry("observedAt", observed)
                .containsEntry("causality", "NOT_ESTABLISHED");
        assertThat(changes.get(0).get("before").toString()).contains("6379");
        assertThat(changes.get(0).get("after").toString()).contains("6380");
        assertThat(repository.observations(incident)).hasSize(1);
        assertThat(repository.observations(incident).get(0).get("snapshot").toString())
                .doesNotContain("scenarioCode");
    }
}
