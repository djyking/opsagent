package com.opsagent.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Identity precedence, environment isolation and the service-only resolver boundary.
 *
 * @author heyu
 * @since 2026/9/3
 */
class AlertTargetResolverTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ItsmPlatformRepository cmdb = mock(ItsmPlatformRepository.class);
    private final AlertTargetResolver resolver = new AlertTargetResolver(cmdb);

    private void active(String target, String environment) {
        when(cmdb.ci(target))
                .thenReturn(
                        Map.of("ciCode", target, "status", "ACTIVE", "environment", environment));
    }

    private ObjectNode labels() {
        return json.createObjectNode()
                .put("service", "opsagent-agent")
                .put("job", "opsagent-platform")
                .put("environment", "PROD");
    }

    @Test
    void canonicalIdentityWinsOverExporterAndKeepsTheActualCmdbEnvironment() {
        active("ops-agent-service", "PROD");
        var labels = labels().put("ci_code", "ops-agent-service");
        var result = resolver.resolve(labels);
        assertThat(result.matched()).isTrue();
        assertThat(result.targetCode()).isEqualTo("ops-agent-service");
        assertThat(result.environment()).isEqualTo("PROD");
        assertThat(result.source()).isEqualTo("ci_code");
        verify(cmdb, never()).ci("ops-platform-service");
    }

    @Test
    void presentEmptyUnknownOrConflictingCanonicalNeverFallsBackToTheJob() {
        active("ops-agent-service", "PROD");
        assertThat(resolver.resolve(labels().put("ci_code", "")).status())
                .isEqualTo("TARGET_INVALID");
        assertThat(resolver.resolve(labels().put("ci_code", "unknown")).status())
                .isEqualTo("TARGET_NOT_ACTIVE");
        assertThat(
                        resolver.resolve(
                                        labels().put("ci_code", "ops-agent-service")
                                                .put("service_ci_code", "ops-platform-service"))
                                .status())
                .isEqualTo("CANONICAL_CONFLICT");
        verify(cmdb, never()).ci("ops-agent-service");
    }

    @Test
    void explicitAliasRequiresActiveCmdbAndMatchingEnvironment() {
        active("ops-agent-service", "PROD");
        var labels = labels().put("job", "opsagent-agent");
        assertThat(resolver.resolve(labels).targetCode()).isEqualTo("ops-agent-service");
        labels.remove("service");
        assertThat(resolver.resolve(labels).source()).isEqualTo("job_alias");
        labels.put("environment", "TEST");
        assertThat(resolver.resolve(labels).status()).isEqualTo("ENVIRONMENT_MISMATCH");
        labels.remove("environment");
        assertThat(resolver.resolve(labels).status()).isEqualTo("ENVIRONMENT_MISSING");
        when(cmdb.ci("ops-agent-service"))
                .thenReturn(Map.of("status", "DISABLED", "environment", "PROD"));
        assertThat(resolver.resolve(labels).status()).isEqualTo("TARGET_NOT_ACTIVE");
    }

    @Test
    void unknownOrConflictingAliasesRemainUnbound() {
        assertThat(
                        resolver.resolve(
                                        json.createObjectNode()
                                                .put("job", "arbitrary-name")
                                                .put("environment", "PROD"))
                                .status())
                .isEqualTo("ALIAS_MISSING");
        assertThat(resolver.resolve(labels()).status()).isEqualTo("ALIAS_CONFLICT");
        verifyNoInteractions(cmdb);
    }

    @Test
    void demoCanonicalTargetMustMatchDemoEnvironmentButDoesNotGrantAnOwner() {
        active("ops-demo-order-service", "DEMO");
        var labels =
                labels().put("ci_code", "ops-demo-order-service")
                        .put("service_ci_code", "ops-demo-order-service")
                        .put("environment", "ISOLATED_DEMO");
        var result = resolver.resolve(labels);
        assertThat(result.matched()).isTrue();
        assertThat(result.environment()).isEqualTo("DEMO");
        labels.put("environment", "PROD");
        assertThat(resolver.resolve(labels).status()).isEqualTo("ENVIRONMENT_MISMATCH");
    }

    @Test
    void canonicalWithoutEnvironmentAndMalformedLabelsRemainUnbound() {
        active("ops-agent-service", "PROD");
        var labels = json.createObjectNode().put("ci_code", "ops-agent-service");
        assertThat(resolver.resolve(labels).status()).isEqualTo("ENVIRONMENT_MISSING");
        labels.put("environment", "PROD").putNull("service_ci_code");
        assertThat(resolver.resolve(labels).status()).isEqualTo("LABEL_INVALID");
    }

    @Test
    void internalEndpointAllowsOnlyTheDedicatedSignedServiceScope() {
        String secret = "alert-target-tests-only-secret-not-a-real-credential";
        var tokens = new InternalActorTokens(secret);
        var controller = new AlertTargetController(resolver, secret);
        String runId = "a".repeat(64);
        var actor =
                new InternalActorTokens.Context(
                        Long.MAX_VALUE,
                        "alertmanager-linker",
                        List.of("SYSTEM"),
                        runId,
                        "alert-target-resolution",
                        Instant.now().plusSeconds(90));
        assertThat(
                        controller
                                .resolve(
                                        "Bearer " + tokens.issue("platform", actor),
                                        json.createObjectNode())
                                .data()
                                .status())
                .isEqualTo("ALIAS_MISSING");
        assertThatThrownBy(() -> controller.resolve(null, labels()))
                .hasMessageContaining("INTERNAL_AUTH");
        assertThatThrownBy(
                        () ->
                                controller.resolve(
                                        "Bearer " + tokens.issue("ticket", actor), labels()))
                .hasMessageContaining("INTERNAL_AUTH");
        for (var wrong :
                List.of(
                        new InternalActorTokens.Context(
                                1,
                                "admin",
                                List.of("ADMIN"),
                                runId,
                                "alert-target-resolution",
                                Instant.now().plusSeconds(90)),
                        new InternalActorTokens.Context(
                                Long.MAX_VALUE,
                                "alertmanager-linker",
                                List.of("SYSTEM"),
                                runId,
                                "ops-demo-order-service",
                                Instant.now().plusSeconds(90)),
                        new InternalActorTokens.Context(
                                Long.MAX_VALUE,
                                "alertmanager-linker",
                                List.of("SYSTEM"),
                                "other-run",
                                "alert-target-resolution",
                                Instant.now().plusSeconds(90)))) {
            assertThatThrownBy(
                            () ->
                                    controller.resolve(
                                            "Bearer " + tokens.issue("platform", wrong), labels()))
                    .hasMessageContaining("ALERT_TARGET_RESOLVER_FORBIDDEN");
        }
    }
}
