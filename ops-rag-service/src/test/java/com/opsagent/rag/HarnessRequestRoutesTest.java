package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * Persistent request ownership remains stable after reopening SQL and concurrent switch changes.
 *
 * @author heyu
 * @since 2026/9/3
 */
class HarnessRequestRoutesTest {
    @TempDir Path directory;

    @Test
    void sqlReopenKeepsOriginalOwnerAndRejectsChangedInputs() {
        var jdbc = database();
        var routes = new HarnessRequestRoutes(jdbc);
        var first =
                routes.assign(
                        "app", "project", "10", "req-1", "digest", "HARNESS", "original-release");
        jdbc.execute("SHUTDOWN");
        routes = new HarnessRequestRoutes(database());
        assertThat(routes.assign("app", "project", "10", "req-1", "digest", "LEGACY"))
                .isEqualTo(first);
        assertThat(
                        routes.assign(
                                        "app",
                                        "project",
                                        "10",
                                        "req-1",
                                        "digest",
                                        "HARNESS",
                                        "new-release")
                                .dispatchJson())
                .isEqualTo("original-release");
        assertThat(routes.assign("app", "project", "10", "req-2", "digest", "LEGACY").owner())
                .isEqualTo("LEGACY");
        var reopened = routes;
        assertThatThrownBy(
                        () -> reopened.assign("app", "project", "10", "req-1", "changed", "LEGACY"))
                .hasMessageContaining("INPUT_CONFLICT");
        assertThat(routes.assign("other", "project", "10", "req-1", "digest", "LEGACY").id())
                .isNotEqualTo(first.id());
    }

    @Test
    void concurrentDifferentSwitchValuesChooseOnePersistentOwner() throws Exception {
        var jdbc = database();
        var routes = new HarnessRequestRoutes(jdbc);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<HarnessRequestRoutes.Route>> jobs = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                String owner = i % 2 == 0 ? "HARNESS" : "LEGACY";
                jobs.add(() -> routes.assign("app", "project", "10", "same", "same-input", owner));
            }
            var futures = pool.invokeAll(jobs);
            var first = futures.get(0).get();
            for (var future : futures) assertThat(future.get()).isEqualTo(first);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_harness_route", Integer.class))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private JdbcTemplate database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:file:"
                                + directory.resolve("routes").toAbsolutePath()
                                + ";MODE=MySQL",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("harness-route-schema.sql"))
                .execute(ds);
        return new JdbcTemplate(ds);
    }
}
