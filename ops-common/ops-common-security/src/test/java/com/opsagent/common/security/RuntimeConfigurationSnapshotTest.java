package com.opsagent.common.security;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

/**
 * Runtime projections preserve resolved provenance and fail closed for identities and secrets.
 *
 * @author heyu
 * @since 2026/9/3
 */
class RuntimeConfigurationSnapshotTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesActualValuesAndIndirectEnvironmentOriginWithoutExposingFullEnvironment()
            throws Exception {
        var environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addLast(
                        new MapPropertySource(
                                "Config resource 'private-directory/application.yml'",
                                Map.of(
                                        "spring.application.name", "ops-rag-service",
                                        "spring.datasource.url",
                                                "${DB_TARGET:jdbc:mysql://default-db:3306/default}",
                                        "ops.rag.top-k", "${RAG_TOP_K:5}",
                                        "ops.ai.providers.openai.api-key", "private-api-key",
                                        "ops.security.secret", "private-signing-key")));
        environment
                .getPropertySources()
                .addFirst(
                        new SystemEnvironmentPropertySource(
                                "systemEnvironment",
                                Map.of(
                                        "DB_TARGET",
                                        "jdbc:mysql://user:db-password@real-db:3306/rag?password=query-secret",
                                        "RAG_TOP_K",
                                        "9",
                                        "UNRELATED_SECRET",
                                        "private-environment")));
        var reader = new RuntimeConfigurationSnapshot(environment);
        var result = reader.snapshot();
        assertThat(result.serviceId()).isEqualTo("ops-rag-service");
        assertThat(result.fields()).hasSize(2);
        assertThat(result.fields())
                .filteredOn(f -> f.key().equals("ops.rag.top-k"))
                .singleElement()
                .satisfies(
                        field -> {
                            assertThat(field.value()).isEqualTo("9");
                            assertThat(field.source()).isEqualTo("应用配置文件 → 环境变量");
                            assertThat(field.verification()).isEqualTo("RUNTIME_RESOLVED");
                        });
        assertThat(result.instanceId()).isEqualTo(reader.snapshot().instanceId());
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(serialized)
                .contains("real-db:3306/rag", "******")
                .doesNotContain(
                        "db-password",
                        "query-secret",
                        "private-api-key",
                        "private-signing-key",
                        "private-environment",
                        "private-directory",
                        "default-db");
        assertThat(result.fields()).noneMatch(f -> f.key().equals("server.port"));
    }

    @Test
    void unresolvedOrDangerousValuesNeverBecomeClaimedDefaults() {
        var environment =
                new MockEnvironment()
                        .withProperty("spring.application.name", "ops-auth-service")
                        .withProperty("spring.data.redis.host", "${MISSING_VALUE}")
                        .withProperty("ops.ai.providers.openai.model", "Bearer private-token");
        var snapshot = new RuntimeConfigurationSnapshot(environment).snapshot();
        assertThat(snapshot.fields())
                .noneMatch(f -> f.value().contains("private-token") || f.value().contains("${"));
        assertThat(snapshot.fields()).noneMatch(f -> f.key().equals("spring.data.redis.host"));
        assertThat(
                        RuntimeConfigurationSnapshot.safeValue(
                                "jdbc:sqlserver://db;password=secret;user=sa"))
                .isEqualTo("jdbc:sqlserver://db");
        assertThat(
                        RuntimeConfigurationSnapshot.safeValue(
                                "https://user:secret@api.example/v1?token=hidden"))
                .isEqualTo("https://******@api.example/v1");
    }

    @Test
    void endpointRequiresRealAdministratorOrOperationsIdentityAndDisablesCaching() {
        var controller =
                new RuntimeConfigurationController(
                        new RuntimeConfigurationSnapshot(
                                new MockEnvironment()
                                        .withProperty(
                                                "spring.application.name", "ops-auth-service")));
        assertThatThrownBy(controller::read).isInstanceOf(BusinessException.class);
        for (String role : List.of("USER", "DEMO")) {
            actor(2, role);
            assertThatThrownBy(controller::read).isInstanceOf(BusinessException.class);
        }
        actor(-1, "ADMIN");
        assertThatThrownBy(controller::read).isInstanceOf(BusinessException.class);
        for (String role : List.of("ADMIN", "ROLE_ADMIN", "OPS", "ROLE_OPS")) {
            actor(2, role);
            assertThat(controller.read().getHeaders().getCacheControl()).isEqualTo("no-store");
        }
    }

    private static void actor(long id, String role) {
        var actor = new OpsPrincipal(id, "test", "test", List.of(role));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }
}
