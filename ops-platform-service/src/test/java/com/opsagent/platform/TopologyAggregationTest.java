package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 独立源故障、真实健康与演练标记分离以及无 Trace 的拓扑契约。
 *
 * @author heyu
 * @since 2026/9/3
 */
class TopologyAggregationTest {
    private final ItsmPlatformService cmdb = mock(ItsmPlatformService.class);
    private final PrometheusAdapter prom = mock(PrometheusAdapter.class);
    private final AlertmanagerAdapter alerts = mock(AlertmanagerAdapter.class);
    private final ObservabilityRepository repository = mock(ObservabilityRepository.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void prometheusFailurePreservesConfiguredNodesAndRelationsAsUnknown() {
        setup(false, true);
        var result = service().topology("ALL", "15m", "HYBRID");
        var tree = json.valueToTree(result);
        assertThat(tree.path("nodes").size()).isEqualTo(2);
        assertThat(tree.path("nodes").path(0).path("health").asText()).isEqualTo("UNKNOWN");
        assertThat(tree.path("nodes").path(0).path("metrics").path("rps").isNull()).isTrue();
        assertThat(tree.path("edges").path(0).path("relationSource").asText())
                .isEqualTo("CONFIGURED");
        assertThat(tree.path("edges").path(0).path("rps").isNull()).isTrue();
    }

    @Test
    void alertmanagerFailureIsNotZeroAlertsAndObservedModeDoesNotInventCalls() {
        setup(true, false);
        var tree = json.valueToTree(service().topology("ALL", "15m", "OBSERVED"));
        assertThat(tree.path("nodes").path(0).path("health").asText()).isEqualTo("UNKNOWN");
        assertThat(tree.path("nodes").path(0).path("activeAlertCount").isNull()).isTrue();
        assertThat(tree.path("edges").isEmpty()).isTrue();
    }

    @Test
    void faultInjectionMarkerDoesNotReplaceIndependentHealthAndEnvironmentIsApplied() {
        setup(true, true);
        when(repository.drilling()).thenReturn(Set.of("ops-rag-service"));
        var tree = json.valueToTree(service().topology("PROD", "15m", "CONFIGURED"));
        assertThat(tree.path("nodes").size()).isEqualTo(1);
        assertThat(tree.path("nodes").path(0).path("drilling").asBoolean()).isTrue();
        assertThat(tree.path("nodes").path(0).path("health").asText()).isEqualTo("UNKNOWN");
        assertThat(tree.path("edges").isEmpty()).isTrue();
    }

    @Test
    void unreachableThirdPartiesReturnDegradedSnapshotsInsteadOfThrowing() {
        var actualProm = new PrometheusAdapter(json);
        var actualAlerts = new AlertmanagerAdapter(json, mock(AlertTargetResolver.class));
        ReflectionTestUtils.setField(actualProm, "baseUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.setField(actualAlerts, "baseUrl", "http://127.0.0.1:1");
        assertThat(actualProm.collect("15m").healthy()).isFalse();
        assertThat(actualAlerts.collect().healthy()).isFalse();
    }

    private void setup(boolean prometheusHealthy, boolean alertHealthy) {
        when(cmdb.cis(null, null))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "ciCode",
                                        "ops-rag-service",
                                        "ciName",
                                        "RAG",
                                        "environment",
                                        "PROD",
                                        "status",
                                        "ACTIVE"),
                                Map.of(
                                        "ciCode",
                                        "redis",
                                        "ciName",
                                        "Redis",
                                        "environment",
                                        "DEMO",
                                        "status",
                                        "ACTIVE")));
        when(cmdb.relations())
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        1,
                                        "sourceCiCode",
                                        "ops-rag-service",
                                        "targetCiCode",
                                        "redis",
                                        "relationType",
                                        "DEPENDS_ON")));
        when(repository.drilling()).thenReturn(Set.of());
        when(repository.layout(any())).thenReturn(Map.of());
        when(prom.collect(any()))
                .thenReturn(
                        new PrometheusAdapter.Snapshot(
                                prometheusHealthy,
                                "source",
                                Instant.now(),
                                prometheusHealthy
                                        ? Map.of(
                                                "up",
                                                List.of(
                                                        new PrometheusAdapter.Sample(
                                                                "opsagent-rag",
                                                                "",
                                                                1,
                                                                Instant.now())))
                                        : Map.of()));
        when(alerts.collect())
                .thenReturn(
                        new AlertmanagerAdapter.Snapshot(
                                alertHealthy, "source", Instant.now(), List.of()));
    }

    @Test
    void sameNamedAlertFromAnotherEnvironmentCannotChangeProductionHealth() {
        setup(true, true);
        when(alerts.collect())
                .thenReturn(
                        new AlertmanagerAdapter.Snapshot(
                                true,
                                "",
                                Instant.now(),
                                List.of(
                                        new AlertmanagerAdapter.Alert(
                                                "demo-alert",
                                                "BusinessFailure",
                                                "CRITICAL",
                                                "",
                                                "",
                                                "ops-rag-service",
                                                "opsagent-rag",
                                                "FIRING",
                                                "DEMO",
                                                "",
                                                ""))));
        var tree = json.valueToTree(service().topology("PROD", "15m", "CONFIGURED"));
        assertThat(tree.path("nodes").path(0).path("health").asText()).isEqualTo("UNKNOWN");
        assertThat(tree.path("nodes").path(0).path("activeAlertCount").asInt()).isZero();
    }

    @Test
    void longRunningActiveAlertUsesLatestConfirmationInsteadOfItsStartAsHealthFreshness() {
        setup(true, true);
        Instant checkedAt = Instant.now();
        String startedAt = checkedAt.minusSeconds(3600).toString();
        when(alerts.collect())
                .thenReturn(
                        new AlertmanagerAdapter.Snapshot(
                                true,
                                "confirmed active",
                                checkedAt,
                                List.of(
                                        new AlertmanagerAdapter.Alert(
                                                "long-running",
                                                "BusinessFailure",
                                                "CRITICAL",
                                                "failure",
                                                startedAt,
                                                "ops-rag-service",
                                                "opsagent-rag",
                                                "FIRING",
                                                "PROD",
                                                "",
                                                ""))));
        var tree = json.valueToTree(service().topology("PROD", "15m", "CONFIGURED"));
        assertThat(tree.path("nodes").path(0).path("health").asText()).isEqualTo("CRITICAL");
        assertThat(tree.path("nodes").path(0).path("observedAt"))
                .isEqualTo(json.valueToTree(checkedAt));
        assertThat(tree.path("activeAlerts").path(0).path("startsAt").asText())
                .isEqualTo(startedAt);
    }

    @Test
    void layoutCannotMixOtherEnvironmentOrDuplicateAndNonFiniteCoordinates() {
        setup(true, true);
        for (var positions :
                List.of(
                        List.of(new ObservabilityDtos.Position("redis", 1, 1)),
                        List.of(
                                new ObservabilityDtos.Position("ops-rag-service", 1, 1),
                                new ObservabilityDtos.Position("ops-rag-service", 2, 2)),
                        List.of(
                                new ObservabilityDtos.Position(
                                        "ops-rag-service", Double.NaN, 1)))) {
            assertThatThrownBy(
                            () ->
                                    service()
                                            .savePersonalLayout(
                                                    new ObservabilityDtos.Layout(
                                                            "PROD", positions)))
                    .isInstanceOf(com.opsagent.common.core.BusinessException.class);
        }
    }

    private TopologyAggregationService service() {
        return new TopologyAggregationService(
                cmdb,
                prom,
                alerts,
                new NodeHealthService(),
                repository,
                mock(PlatformAuditRepository.class),
                json);
    }
}
