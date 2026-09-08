package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verify source timestamps, identity, native read evidence and infrastructure failure semantics.
 *
 * @author heyu
 * @since 2026/9/3
 */
class InfrastructureObservationContractTest {
    private final Instant now = Instant.parse("2026-09-07T10:00:00Z");
    private final NodeObservationService observations = new NodeObservationService();

    @Test
    void mysqlRequiresRealReadAndRuntimeFieldsInsteadOfExporterUp() {
        assertThat(health("mysql", Map.of()).health()).isEqualTo("UNKNOWN");
        assertThat(health("mysql", complete("mysql")).health()).isEqualTo("HEALTHY");
        var missing = complete("mysql");
        missing.remove("mysqlMaxConnections");
        assertThat(health("mysql", missing).reasonCode()).isEqualTo("INFRA_EVIDENCE_INCOMPLETE");
    }

    @Test
    void currentScrapeCannotRenewAnExpiredNativeCheck() {
        var source = mysqlSnapshot(now.minusSeconds(120), "db:3306", false);
        var evidence = observations.metricEvidence(source, "15m", now, 90);
        assertThat(evidence.get("mysqlQuerySuccess").value()).isNull();
        assertThat(evidence.get("mysqlQuerySuccess").sampledAt()).isEqualTo(now.minusSeconds(120));
        var metrics = new LinkedHashMap<String, Object>();
        evidence.forEach((key, item) -> metrics.put(key, item.value()));
        assertThat(health("mysql", metrics).health()).isEqualTo("UNKNOWN");
    }

    @Test
    void checkTimestampCannotBeBorrowedFromAnotherInstance() {
        var evidence =
                observations.metricEvidence(
                        mysqlSnapshot(now, "other:3306", false), "15m", now, 90);
        assertThat(evidence.get("mysqlQuerySuccess").value()).isNull();
        assertThat(evidence.get("mysqlQuerySuccess").sampledAt()).isNull();
    }

    @Test
    void missingNativeEvidenceFromOneExpectedInstanceIsNotHidden() {
        var evidence =
                observations.metricEvidence(mysqlSnapshot(now, "db:3306", true), "15m", now, 90);
        assertThat(evidence.get("mysqlConnections").value()).isNull();
    }

    @Test
    void successfulMysqlEvidenceUsesItsOwnCheckTime() {
        var checked = now.minusSeconds(12);
        var evidence =
                observations.metricEvidence(
                        mysqlSnapshot(checked, "db:3306", false), "15m", now, 90);
        assertThat(evidence.get("mysqlQuerySuccess").value()).isEqualTo(1);
        assertThat(evidence.get("mysqlQuerySuccess").sampledAt()).isEqualTo(checked);
    }

    @Test
    void elasticYellowIsDegradedButNotCriticalSearchFailure() {
        var metrics = complete("elasticsearch");
        metrics.put("elasticClusterStatus", 1);
        assertThat(health("elasticsearch", metrics).health()).isEqualTo("DEGRADED");
        assertThat(health("elasticsearch", metrics).reason()).contains("不等同搜索不可用");
        metrics.put("elasticClusterStatus", 2);
        assertThat(health("elasticsearch", metrics).health()).isEqualTo("CRITICAL");
    }

    @Test
    void authFailureDoesNotClaimThatRedisIsDown() {
        var metrics = complete("redis");
        metrics.put("infrastructureReadSuccess", 0);
        metrics.put("infrastructureAuthSuccess", 0);
        assertThat(health("redis", metrics).health()).isEqualTo("UNKNOWN");
        assertThat(health("redis", metrics).reasonCode()).isEqualTo("INFRA_AUTH_REJECTED");
    }

    @Test
    void prometheusDownstreamFailuresAreSeparateFromCollectorReadiness() {
        var metrics = complete("prometheus");
        metrics.put("prometheusFailedTargets", 2);
        assertThat(health("prometheus", metrics).reasonCode())
                .isEqualTo("DOWNSTREAM_SCRAPE_FAILED");
        metrics.put("prometheusReady", 0);
        assertThat(health("prometheus", metrics).reasonCode()).isEqualTo("INFRA_CHECK_FAILED");
    }

    @Test
    void nativeReadFailureIsCollectionFailureEvenWhenExporterScrapeSucceeds() {
        var source = mysqlSnapshot(now, "db:3306", false);
        var evidence = new LinkedHashMap<>(observations.metricEvidence(source, "15m", now, 90));
        evidence.put(
                "infrastructureReadSuccess",
                new ObservabilityDtos.MetricEvidence(
                        0.0, "boolean", null, now, "OBSERVED", "NATIVE_METRICS"));
        var observed =
                observations.observe(
                        Map.of("ciCode", "mysql", "environment", "PROD"),
                        "opsagent-mysql",
                        source,
                        evidence,
                        now,
                        90,
                        null,
                        null);
        assertThat(observed.status()).isEqualTo("FAILED");
        assertThat(observed.reasonCode()).isEqualTo("INFRA_CHECK_FAILED");
    }

    private LinkedHashMap<String, Object> complete(String kind) {
        var values = new LinkedHashMap<String, Object>();
        InfrastructureObservationContract.required("opsagent-" + kind)
                .forEach(key -> values.put(key, 1));
        values.put("elasticClusterStatus", 0);
        values.put("prometheusFailedTargets", 0);
        return values;
    }

    private NodeHealthService.Health health(String kind, Map<String, Object> metrics) {
        return new NodeHealthService()
                .compute(
                        "opsagent-" + kind,
                        "ACTIVE",
                        List.of(new PrometheusAdapter.Sample("opsagent-" + kind, "", 1, now)),
                        List.of(),
                        metrics,
                        now,
                        null,
                        null);
    }

    private PrometheusAdapter.Snapshot mysqlSnapshot(
            Instant checked, String clockInstance, boolean secondTarget) {
        var labels =
                Map.of(
                        "job",
                        "opsagent-mysql",
                        "ci_code",
                        "mysql",
                        "environment",
                        "PROD",
                        "instance",
                        "db:3306");
        var raw = new ArrayList<PrometheusAdapter.Sample>();
        for (String key :
                List.of(
                        "mysql_query_success",
                        "mysql_connections",
                        "mysql_max_connections",
                        "read_success",
                        "auth_success")) {
            var valueLabels = new LinkedHashMap<>(labels);
            valueLabels.put("__name__", "opsagent_infra_" + key);
            raw.add(new PrometheusAdapter.Sample("opsagent-mysql", "", 1, now, valueLabels, now));
        }
        var timeLabels = new LinkedHashMap<>(labels);
        timeLabels.put("__name__", "opsagent_infra_check_timestamp_seconds");
        timeLabels.put("instance", clockInstance);
        raw.add(
                new PrometheusAdapter.Sample(
                        "opsagent-mysql", "", checked.getEpochSecond(), now, timeLabels, now));
        var targets = new ArrayList<PrometheusAdapter.Target>();
        targets.add(
                new PrometheusAdapter.Target(
                        labels, "http://collector/metrics/mysql", "up", "", now, "10s"));
        if (secondTarget) {
            var second = new LinkedHashMap<>(labels);
            second.put("instance", "db-two:3306");
            targets.add(
                    new PrometheusAdapter.Target(
                            second, "http://collector/metrics/mysql-two", "up", "", now, "10s"));
        }
        return new PrometheusAdapter.Snapshot(
                true,
                "",
                now,
                Map.of(
                        "raw",
                        raw,
                        "up",
                        List.of(
                                new PrometheusAdapter.Sample(
                                        "opsagent-mysql", "", 1, now, labels, now))),
                targets,
                Map.of());
    }
}
