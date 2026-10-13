package com.opsagent.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.ConfigurationExecutionEvidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Executor evidence authenticates independently and never equates a new disk file with loaded
 * config.
 *
 * @author heyu
 * @since 2026/9/3
 */
class RuntimeConfigurationEvidenceTest {
    @TempDir Path directory;

    @Test
    void capturedVersionAndSecretDigestRemainSafeAfterDiskChanges() throws Exception {
        Path file = directory.resolve("managed.yml");
        Files.writeString(file, "maximum: 12\n");
        var environment = environment(file);
        var reader = new RuntimeConfigurationEvidence(environment);
        String originalVersion = reader.snapshot().configurationFiles().get(0).loadedVersion();
        Files.writeString(file, "maximum: 99\n");
        var snapshot = reader.snapshot();
        assertThat(snapshot.configurationFiles().get(0).loadedVersion())
                .isEqualTo(originalVersion)
                .hasSize(64);
        assertThat(snapshot.fields())
                .filteredOn(field -> field.key().equals("spring.datasource.password"))
                .singleElement()
                .satisfies(
                        field -> {
                            assertThat(field.value()).isNull();
                            assertThat(field.valueDigest()).hasSize(64);
                        });
        assertThat(snapshot.fields())
                .filteredOn(field -> field.key().equals("ops.agent.run-token-budget"))
                .singleElement()
                .satisfies(field -> assertThat(field.value()).isEqualTo("40000"));
        String result = new ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot);
        assertThat(result).doesNotContain("not-for-response", directory.toString());
    }

    @Test
    void hmacRequiresLoopbackFreshTimestampAndSingleUseNonce() {
        String secret = "independent-executor-secret-0123456789";
        var proof =
                new ConfigurationExecutionEvidence(
                        "ops-auth-service", secret, "", "", ignored -> false);
        String stamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "a".repeat(32);
        String signature =
                proof.hash(stamp + "\n" + nonce + "\nGET\n" + ConfigurationExecutionEvidence.PATH);
        assertThat(proof.authorized("10.0.0.5", stamp, nonce, signature)).isFalse();
        assertThat(proof.authorized("127.0.0.1", stamp, nonce, signature)).isTrue();
        assertThat(proof.authorized("127.0.0.1", stamp, nonce, signature)).isFalse();
        String old = String.valueOf(Instant.now().minusSeconds(90).getEpochSecond());
        assertThat(
                        proof.authorized(
                                "127.0.0.1",
                                old,
                                "b".repeat(32),
                                proof.hash(
                                        old
                                                + "\n"
                                                + "b".repeat(32)
                                                + "\nGET\n"
                                                + ConfigurationExecutionEvidence.PATH)))
                .isFalse();
    }

    @Test
    void explicitPrivateExecutorStillRequiresValidHmacAndSingleUseNonce() {
        String secret = "independent-executor-secret-0123456789";
        var proof =
                new ConfigurationExecutionEvidence(
                        "ops-auth-service", secret, "", "", ignored -> false, "172.20.0.1");
        String stamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "c".repeat(32);
        String signature =
                proof.hash(stamp + "\n" + nonce + "\nGET\n" + ConfigurationExecutionEvidence.PATH);
        assertThat(proof.authorized("172.20.0.2", stamp, nonce, signature)).isFalse();
        assertThat(proof.authorized("172.20.0.1", stamp, nonce, "0".repeat(64))).isFalse();
        assertThat(proof.authorized("172.20.0.1", stamp, nonce, signature)).isTrue();
        assertThat(proof.authorized("172.20.0.1", stamp, nonce, signature)).isFalse();
        for (String invalid :
                java.util.List.of(
                        "example.com", "8.8.8.8", "172.32.0.1", "172.20.999.1", "0172.20.0.1"))
            assertThat(ConfigurationExecutionEvidence.privateAddress(invalid)).isFalse();
    }

    @Test
    void effectiveEnvironmentOverrideCannotBecomeSuccessfulFileAdoption() throws Exception {
        Path file = directory.resolve("managed.yml");
        Files.writeString(file, "pool: 12\n");
        var environment = environment(file);
        environment
                .getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                "systemEnvironment",
                                Map.of("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "27")));
        var reader = new RuntimeConfigurationEvidence(environment);
        assertThat(reader.snapshot().fields())
                .filteredOn(
                        field -> field.key().equals("spring.datasource.hikari.maximum-pool-size"))
                .singleElement()
                .satisfies(
                        field -> {
                            assertThat(field.value()).isEqualTo("27");
                            assertThat(field.overridden()).isTrue();
                        });
    }

    @Test
    void gatewayRouteArraysHaveDeterministicLoadedValueDigest() throws Exception {
        Path file = directory.resolve("managed.yml");
        Files.writeString(file, "routes: []\n");
        var environment = environment(file);
        environment.setProperty(
                "ops.configuration.managed-keys", "spring.cloud.gateway.server.webflux.routes");
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "config resource managed.yml routes",
                                Map.of(
                                        "spring.cloud.gateway.server.webflux.routes[0].id", "auth",
                                        "spring.cloud.gateway.server.webflux.routes[0].uri",
                                                "http://localhost",
                                        "spring.cloud.gateway.server.webflux.routes[0].predicates[0]",
                                                "Path=/api/auth/**")));
        var reader = new RuntimeConfigurationEvidence(environment);
        var field = reader.snapshot().fields().get(0);
        var proof =
                new ConfigurationExecutionEvidence(
                        "ops-auth-service",
                        "independent-executor-secret-0123456789",
                        "",
                        "",
                        ignored -> false);
        assertThat(field.valueDigest())
                .isEqualTo(
                        proof.hash(
                                "{\"[0].id\":\"auth\",\"[0].predicates[0]\":\"Path=/api/auth/**\",\"[0].uri\":\"http://localhost\"}"));
    }

    private MockEnvironment environment(Path file) {
        var result =
                new MockEnvironment()
                        .withProperty("spring.application.name", "ops-auth-service")
                        .withProperty(
                                "ops.configuration.executor.secret",
                                "independent-executor-secret-0123456789")
                        .withProperty("ops.configuration.managed-files", file.toString())
                        .withProperty(
                                "ops.configuration.managed-keys",
                                "spring.datasource.password,spring.datasource.hikari.maximum-pool-size,"
                                    + "ops.agent.run-token-budget");
        result.getPropertySources()
                .addLast(
                        new MapPropertySource(
                                "Config resource managed.yml",
                                Map.of(
                                        "spring.datasource.password",
                                        "not-for-response",
                                        "spring.datasource.hikari.maximum-pool-size",
                                        "12",
                                        "ops.agent.run-token-budget",
                                        "40000")));
        return result;
    }
}
