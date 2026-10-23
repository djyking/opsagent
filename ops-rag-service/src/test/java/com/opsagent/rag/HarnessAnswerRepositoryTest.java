package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * @author heyu @since 2026/9/3
 */
class HarnessAnswerRepositoryTest {
    HarnessAnswerRepository repository;

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("harness-answer-schema.sql"))
                .execute(ds);
        repository = new HarnessAnswerRepository(new JdbcTemplate(ds));
    }

    @Test
    void ownerAndInputRemainFixedAcrossRolloutAndRollback() {
        var first =
                repository.assign("app", "project", 1, "request-0001", "{\"q\":\"a\"}", "HARNESS");
        var replay =
                repository.assign("app", "project", 1, "request-0001", "{\"q\":\"a\"}", "LEGACY");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.owner()).isEqualTo("HARNESS");
        assertThatThrownBy(
                        () ->
                                repository.assign(
                                        "app",
                                        "project",
                                        1,
                                        "request-0001",
                                        "{\"q\":\"different\"}",
                                        "LEGACY"))
                .hasMessageContaining("INPUT_CONFLICT");
        assertThat(
                        repository
                                .assign(
                                        "app",
                                        "project",
                                        2,
                                        "request-0001",
                                        "{\"q\":\"a\"}",
                                        "LEGACY")
                                .id())
                .isNotEqualTo(first.id());
    }

    @Test
    void concurrentLegacyClaimsAllowOnlyOneGenerationAndUnknownNeverReclaims() throws Exception {
        var row = repository.assign("app", "project", 1, "request-0002", "{}", "LEGACY");
        var pool = Executors.newFixedThreadPool(8);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int n = 0; n < 20; n++) jobs.add(pool.submit(() -> repository.claim(row)));
            int claims = 0;
            for (var job : jobs) if (job.get()) claims++;
            assertThat(claims).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        repository.unknown(row.id());
        assertThat(repository.get(row.id()).state()).isEqualTo("UNKNOWN");
        assertThat(repository.claim(row)).isFalse();
    }

    @Test
    void platformBindingIsIdempotentAndCannotPointToAnotherRun() {
        var row = repository.assign("app", "project", 1, "request-0003", "{}", "HARNESS");
        assertThat(repository.claim(row)).isTrue();
        repository.prepared(row.id(), "{}", "a".repeat(64), "{\"frozen\":true}");
        String run = UUID.randomUUID().toString();
        repository.bind(row.id(), run);
        repository.bind(row.id(), run);
        assertThat(repository.get(row.id()).dispatch()).isEqualTo("{\"frozen\":true}");
        assertThatThrownBy(() -> repository.bind(row.id(), UUID.randomUUID().toString()))
                .hasMessageContaining("BINDING_CONFLICT");
    }
}
