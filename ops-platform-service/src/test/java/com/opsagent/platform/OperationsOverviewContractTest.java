package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 验证缺失服务、过期目标与用户治理缓存不会被自动巡检误报为完整健康。
 *
 * @author heyu
 * @since 2026/9/3
 */
class OperationsOverviewContractTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MonitoringService monitoring = mock(MonitoringService.class);
    private final ItsmPlatformRepository cmdb = mock(ItsmPlatformRepository.class);
    private final OperationsIntegrationClient integrations =
            mock(OperationsIntegrationClient.class);
    private final OperationsOverviewService service =
            new OperationsOverviewService(
                    monitoring, cmdb, integrations, new OperationsTrendAnalyzer());

    @Test
    void shouldFillMissingServicesAndSeriesAsUnknownWithoutLeakingInternalAddresses()
            throws Exception {
        when(monitoring.summary())
                .thenReturn(
                        Map.of(
                                "services",
                                List.of(
                                        Map.of(
                                                "job",
                                                "opsagent-auth",
                                                "health",
                                                "up",
                                                "lastScrape",
                                                Instant.now().toString(),
                                                "scrapeUrl",
                                                "http://internal-sensitive-host:8101",
                                                "lastError",
                                                "sensitive-error"))));
        when(monitoring.queryRange(anyString(), any(), any(), anyInt()))
                .thenReturn(json.readTree("{\"status\":\"success\",\"data\":{\"result\":[]}}"));
        var result = service.inspection();
        assertThat(result.targets()).hasSize(8);
        assertThat(result.targets())
                .extracting(OperationsDtos.Target::service)
                .contains("opsagent-agent", "opsagent-demo-order");
        assertThat(result.targets().stream().filter(target -> "unknown".equals(target.health())))
                .hasSize(7);
        assertThat(result.metrics())
                .hasSize(16)
                .allMatch(metric -> "UNKNOWN".equals(metric.status()));
        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(json.writeValueAsString(result))
                .doesNotContain("internal-sensitive-host", "sensitive-error");
        verifyNoInteractions(integrations);
    }

    @Test
    void shouldUseRegisteredServiceJobForTargetsAndMetricsWithoutAddingHosts() throws Exception {
        String localJob = "opsagent-phase2-isolated-demo";
        String hostJob = "opsagent-windows-host";
        when(cmdb.cis(null, null))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "ciCode",
                                        "ops-demo-order-service",
                                        "ciType",
                                        "SERVICE",
                                        "metadataJson",
                                        "{\"bindings\":{\"prometheusJob\":\"" + localJob + "\"}}"),
                                Map.of(
                                        "ciCode",
                                        "ops-local-windows-host",
                                        "ciType",
                                        "HOST",
                                        "metadataJson",
                                        "{\"bindings\":{\"prometheusJob\":\"" + hostJob + "\"}}")));
        when(monitoring.summary())
                .thenReturn(
                        Map.of(
                                "services",
                                List.of(
                                        Map.of(
                                                "job",
                                                localJob,
                                                "health",
                                                "up",
                                                "lastScrape",
                                                Instant.now().toString()),
                                        Map.of(
                                                "job",
                                                localJob,
                                                "health",
                                                "down",
                                                "lastScrape",
                                                Instant.now().toString()),
                                        Map.of(
                                                "job",
                                                "opsagent-demo-order",
                                                "health",
                                                "up",
                                                "lastScrape",
                                                Instant.now().toString()),
                                        Map.of(
                                                "job",
                                                hostJob,
                                                "health",
                                                "up",
                                                "lastScrape",
                                                Instant.now().toString()))));
        var rows =
                List.of(localJob, "opsagent-demo-order", hostJob).stream()
                        .map(
                                job ->
                                        Map.of(
                                                "metric",
                                                Map.of("job", job),
                                                "values",
                                                List.of(
                                                        List.of(
                                                                Instant.now().getEpochSecond(),
                                                                "42"))))
                        .toList();
        when(monitoring.queryRange(anyString(), any(), any(), anyInt()))
                .thenReturn(
                        json.valueToTree(
                                Map.of("status", "success", "data", Map.of("result", rows))));

        var result = service.inspection();
        assertThat(result.targets()).hasSize(8);
        assertThat(result.targets())
                .extracting(OperationsDtos.Target::service)
                .contains(localJob)
                .doesNotContain("opsagent-demo-order", hostJob);
        assertThat(
                        result.targets().stream()
                                .filter(target -> localJob.equals(target.service()))
                                .findFirst())
                .get()
                .satisfies(
                        target -> {
                            assertThat(target.ciCode()).isEqualTo("ops-demo-order-service");
                            assertThat(target.health()).isEqualTo("down");
                        });
        assertThat(result.metrics()).hasSize(16);
        assertThat(result.metrics())
                .extracting(OperationsDtos.Metric::job)
                .contains(localJob)
                .doesNotContain("opsagent-demo-order", hostJob);
        assertThat(result.metrics().stream().filter(metric -> localJob.equals(metric.job())))
                .hasSize(2)
                .allMatch(metric -> Double.valueOf(42).equals(metric.currentValue()));
        verify(cmdb, times(1)).cis(null, null);
    }

    @Test
    void shouldNotTreatExpiredScrapeAsHealthy() {
        when(monitoring.summary())
                .thenReturn(
                        Map.of(
                                "services",
                                List.of(
                                        Map.of(
                                                "job",
                                                "opsagent-rag",
                                                "health",
                                                "up",
                                                "lastScrape",
                                                Instant.now().minusSeconds(1000).toString()))));
        assertThat(
                        service.targets().stream()
                                .filter(target -> "opsagent-rag".equals(target.service()))
                                .findFirst())
                .get()
                .extracting(OperationsDtos.Target::health)
                .isEqualTo("unknown");
    }

    @Test
    void shouldKeepBackgroundInspectionOutOfUserGovernanceCache() throws Exception {
        when(monitoring.summary()).thenReturn(Map.of("services", List.of()));
        when(monitoring.queryRange(anyString(), any(), any(), anyInt()))
                .thenReturn(json.readTree("{\"status\":\"success\",\"data\":{\"result\":[]}}"));
        when(integrations.nacos())
                .thenReturn(
                        new OperationsDtos.Nacos(
                                "AVAILABLE", "metadata", 6, 6, 1, List.of(), List.of()));
        when(integrations.sentinel())
                .thenReturn(
                        new OperationsDtos.Sentinel(
                                "AVAILABLE",
                                "runtime",
                                "Sentinel",
                                List.of(),
                                10.0,
                                0.0,
                                Instant.now().toString()));
        var first = service.overview(60, false);
        assertThat(service.inspection().sentinel().status()).isEqualTo("NOT_COLLECTED");
        var next = service.overview(60, false);
        assertThat(next).isSameAs(first);
        assertThat(next.sentinel().status()).isEqualTo("AVAILABLE");
        verify(integrations, times(1)).sentinel();
        verify(integrations, times(1)).nacos();
    }

    @Test
    void shouldAcceptObservedZeroErrorsOnlyFromGuardedRequestRateQuery() throws Exception {
        when(monitoring.summary()).thenReturn(Map.of("services", List.of()));
        when(monitoring.queryRange(anyString(), any(), any(), anyInt()))
                .thenAnswer(
                        invocation -> {
                            String query = invocation.getArgument(0);
                            if (query.contains("http_server_requests")) {
                                assertThat(query)
                                        .contains("or on(job)", " > 0", "min by(job)(up) == 1");
                                return json.valueToTree(
                                        Map.of(
                                                "status",
                                                "success",
                                                "data",
                                                Map.of(
                                                        "result",
                                                        List.of(
                                                                Map.of(
                                                                        "metric",
                                                                        Map.of(
                                                                                "job",
                                                                                "opsagent-auth"),
                                                                        "values",
                                                                        List.of(
                                                                                List.of(
                                                                                        Instant
                                                                                                .now()
                                                                                                .getEpochSecond(),
                                                                                        "0")))))));
                            }
                            return json.readTree(
                                    "{\"status\":\"success\",\"data\":{\"result\":[]}}");
                        });
        var result = service.inspection();
        var error =
                result.metrics().stream()
                        .filter(
                                metric ->
                                        "http5xx".equals(metric.id())
                                                && "opsagent-auth".equals(metric.job()))
                        .findFirst()
                        .orElseThrow();
        assertThat(error.currentValue()).isZero();
        assertThat(error.status()).isEqualTo("OK");
        assertThat(
                        result.metrics().stream()
                                .filter(metric -> "opsagent-ticket".equals(metric.job())))
                .allMatch(metric -> "UNKNOWN".equals(metric.status()));
    }
}
