package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 采集与业务状态分离、严格身份和真实指标时间的回归。
 *
 * @author heyu
 * @since 2026/9/3
 */
class NodeObservationTruthTest {
    private final NodeObservationService observations = new NodeObservationService();
    private final Instant now = Instant.now();
    private final Map<String, Object> ci =
            Map.of(
                    "ciCode",
                    "rabbitmq",
                    "environment",
                    "PROD",
                    "ciType",
                    "MESSAGE_QUEUE",
                    "status",
                    "ACTIVE");

    @Test
    void exporterFailureDoesNotOverrideIndependentSuccessfulBusinessProbe() {
        var up = sample("up", "PROD", 0, now);
        var target =
                new PrometheusAdapter.Target(
                        up.labels(),
                        "http://rabbitmq:15692/metrics",
                        "down",
                        "server returned HTTP status 403 Forbidden",
                        now,
                        "15s");
        var snapshot =
                new PrometheusAdapter.Snapshot(
                        true, "", now, Map.of("up", List.of(up)), List.of(target), Map.of());
        var observation = observations.observe(ci, "j", snapshot, Map.of(), now, 90, 1.0, now);
        var health =
                new NodeHealthService()
                        .compute("j", "ACTIVE", List.of(up), List.of(), Map.of(), now, 1.0, now);
        assertThat(observation.status()).isEqualTo("FAILED");
        assertThat(observation.reasonCode()).isEqualTo("TARGET_AUTH_REJECTED");
        assertThat(health.health()).isEqualTo("HEALTHY");
        assertThat(health.scope()).isEqualTo("BUSINESS_PROBE");
        assertThat(observation.instances())
                .singleElement()
                .satisfies(
                        instance -> {
                            assertThat(instance.identity().environment()).isEqualTo("PROD");
                            assertThat(instance.up()).isZero();
                        });
    }

    @Test
    void collectionAlertDoesNotBecomeBusinessCriticalEvenWhenSeverityWasLegacyCritical() {
        var alert =
                new AlertmanagerAdapter.Alert(
                        "a",
                        "OpsAgentServiceDown",
                        "CRITICAL",
                        "scrape failed",
                        now.toString(),
                        "rabbitmq",
                        "j",
                        "FIRING");
        var state =
                new NodeHealthService()
                        .compute(
                                "j",
                                "ACTIVE",
                                List.of(sample("up", "PROD", 0, now)),
                                List.of(alert),
                                Map.of(),
                                now,
                                null,
                                null);
        assertThat(state.health()).isEqualTo("UNKNOWN");
        assertThat(state.reasonCode()).isEqualTo("BUSINESS_EVIDENCE_MISSING");
    }

    @Test
    void prodAndDemoWithSameJobCannotBorrowMetricsOrTargets() {
        var prod = sample("up", "PROD", 1, now);
        var demo = sample("up", "DEMO", 0, now);
        var source =
                new PrometheusAdapter.Snapshot(true, "", now, Map.of("up", List.of(prod, demo)));
        var scoped = observations.scoped(source, "j", "rabbitmq", "PROD", "", "", false);
        assertThat(scoped.series().get("up")).containsExactly(prod);
        assertThat(observations.matches(Map.of("job", "j"), "j", "rabbitmq", "PROD", "", "", false))
                .isFalse();
    }

    @Test
    void currentExplicitTargetExcludesOldUnlabeledLookbackAndRateSeries() {
        var current = migrationSample("", 12, now, true);
        var oldEvaluation = migrationSample("", 80, now, false);
        var counter =
                migrationSample(
                        "http_server_requests_seconds_count", 20, now.minusSeconds(5), true);
        var source =
                new PrometheusAdapter.Snapshot(
                        true,
                        "",
                        now,
                        Map.of(
                                "rps",
                                List.of(oldEvaluation, current),
                                "cpuUsage",
                                List.of(oldEvaluation, current),
                                "raw",
                                List.of(
                                        counter,
                                        migrationSample(
                                                "process_cpu_usage",
                                                0.12,
                                                now.minusSeconds(5),
                                                true),
                                        migrationSample(
                                                "process_cpu_usage",
                                                0.8,
                                                now.minusSeconds(300),
                                                false))),
                        List.of(
                                new PrometheusAdapter.Target(
                                        current.labels(),
                                        "http://one:1/metrics",
                                        "up",
                                        "",
                                        now,
                                        "15s")),
                        Map.of());
        var scoped = observations.scoped(source, "j", "rabbitmq", "PROD", "", "", true);
        var evidence = observations.metricEvidence(scoped, "15m", now, 90);
        assertThat(scoped.series().get("rps")).containsExactly(current);
        assertThat(evidence.get("rps").value()).isEqualTo(12);
        assertThat(evidence.get("cpuUsage").value()).isEqualTo(12);
        assertThat(evidence.get("rps").sampledAt()).isEqualTo(counter.observedAt());
    }

    @Test
    void explicitTargetWithoutCurrentSamplesCannotBorrowLegacyHealthyValues() {
        var oldUp = migrationSample("up", 1, now, false);
        var current = migrationSample("", 0, now, true);
        var source =
                new PrometheusAdapter.Snapshot(
                        true,
                        "",
                        now,
                        Map.of(
                                "up",
                                List.of(oldUp),
                                "cpuUsage",
                                List.of(migrationSample("", 4, now, false)),
                                "raw",
                                List.of(migrationSample("process_cpu_usage", 0.04, now, false))),
                        List.of(
                                new PrometheusAdapter.Target(
                                        current.labels(),
                                        "http://one:1/metrics",
                                        "unknown",
                                        "",
                                        now,
                                        "15s")),
                        Map.of());
        var scoped = observations.scoped(source, "j", "rabbitmq", "PROD", "", "", true);
        assertThat(scoped.series().get("up")).isEmpty();
        assertThat(observations.metricEvidence(scoped, "15m", now, 90).get("cpuUsage").value())
                .isNull();
        assertThat(
                        new NodeHealthService()
                                .compute(
                                        "j",
                                        "ACTIVE",
                                        scoped.series().get("up"),
                                        List.of(),
                                        Map.of(),
                                        now,
                                        null,
                                        null)
                                .health())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void genuinelyUnlabeledCurrentTargetRetainsLegacyBinding() {
        var legacy = migrationSample("up", 1, now, false);
        var source =
                new PrometheusAdapter.Snapshot(
                        true,
                        "",
                        now,
                        Map.of("up", List.of(legacy)),
                        List.of(
                                new PrometheusAdapter.Target(
                                        legacy.labels(),
                                        "http://one:1/metrics",
                                        "up",
                                        "",
                                        now,
                                        "15s")),
                        Map.of());
        var scoped = observations.scoped(source, "j", "rabbitmq", "PROD", "", "", true);
        assertThat(scoped.series().get("up")).containsExactly(legacy);
        assertThat(scoped.targets()).hasSize(1);
    }

    @Test
    void zeroRequiresFreshRawCounterAndNoRequestsDoNotInventErrorRateOrLatency() {
        var counter = sample("http_server_requests_seconds_count", "PROD", 24, now.minusSeconds(5));
        var snapshot =
                new PrometheusAdapter.Snapshot(
                        true,
                        "",
                        now,
                        Map.of(
                                "rps",
                                List.of(sample("", "PROD", 0, now)),
                                "raw",
                                List.of(counter)));
        var evidence = observations.metricEvidence(snapshot, "5m", now, 90);
        assertThat(evidence.get("rps").value()).isZero();
        assertThat(evidence.get("rps").sampledAt()).isEqualTo(counter.observedAt());
        assertThat(evidence.get("errorRate").value()).isNull();
        assertThat(evidence.get("p95Ms").value()).isNull();
        assertThat(evidence.get("rps").windowSeconds()).isEqualTo(300);
        assertThat(
                        observations
                                .metricEvidence(
                                        new PrometheusAdapter.Snapshot(
                                                true,
                                                "",
                                                now,
                                                Map.of("rps", List.of(sample("", "PROD", 0, now)))),
                                        "5m",
                                        now,
                                        90)
                                .get("rps")
                                .value())
                .isNull();
    }

    @Test
    void refreshCannotRenewStaleRawMetricTimestamp() {
        Instant old = now.minusSeconds(95);
        var snapshot =
                new PrometheusAdapter.Snapshot(
                        true,
                        "",
                        now,
                        Map.of(
                                "up", List.of(sample("up", "PROD", 1, old)),
                                "cpuUsage", List.of(sample("", "PROD", 14, now)),
                                "raw", List.of(sample("process_cpu_usage", "PROD", 0.14, old))));
        var evidence = observations.metricEvidence(snapshot, "15m", now, 90);
        assertThat(evidence.get("cpuUsage").value()).isNull();
        assertThat(evidence.get("cpuUsage").sampledAt()).isEqualTo(old);
        var observation = observations.observe(ci, "j", snapshot, evidence, now, 90, null, null);
        assertThat(observation.status()).isEqualTo("STALE");
        assertThat(observation.sampledAt()).isEqualTo(old);
        assertThat(observation.fetchedAt()).isEqualTo(now);
    }

    @Test
    void nativeRabbitZeroIsObservedAndUnconfiguredObjectsAreExplicit() {
        var snapshot =
                new PrometheusAdapter.Snapshot(
                        true,
                        "",
                        now,
                        Map.of(
                                "raw",
                                List.of(sample("rabbitmq_queue_messages_ready", "PROD", 0, now))));
        var evidence = observations.metricEvidence(snapshot, "15m", now, 90);
        assertThat(evidence.get("messagesReady").value()).isZero();
        assertThat(evidence.get("messagesReady").unit()).isEqualTo("messages");
        assertThat(evidence.get("messagesReady").sampledAt()).isEqualTo(now);
        assertThat(observations.observe(ci, "", snapshot, evidence, now, 90, null, null).status())
                .isEqualTo("NOT_CONFIGURED");
        assertThat(
                        observations
                                .observe(
                                        Map.of(
                                                "ciCode",
                                                "external-llm",
                                                "environment",
                                                "PROD",
                                                "ciType",
                                                "EXTERNAL_API"),
                                        "",
                                        snapshot,
                                        Map.of(),
                                        now,
                                        90,
                                        null,
                                        null)
                                .status())
                .isEqualTo("UNSUPPORTED");
    }

    @Test
    void prometheusOutageCannotEstablishZeroOrOneBusinessFailurePerCi() {
        var snapshot = new PrometheusAdapter.Snapshot(false, "source down", now, Map.of());
        assertThat(
                        observations
                                .observe(ci, "j", snapshot, Map.of(), now, 90, null, null)
                                .reasonCode())
                .isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(
                        new NodeHealthService()
                                .compute(
                                        "j",
                                        "MAINTENANCE",
                                        List.of(),
                                        List.of(),
                                        Map.of(),
                                        now,
                                        null,
                                        null)
                                .health())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void missingSecondConfiguredInstanceCannotProduceCompleteCoverageOrHealthyRuntime() {
        var up = sample("up", "PROD", 1, now);
        var one =
                new PrometheusAdapter.Target(
                        up.labels(), "http://one:1/metrics", "up", "", now, "15s");
        var two =
                new PrometheusAdapter.Target(
                        Map.of("job", "j", "instance", "two", "environment", "PROD"),
                        "http://two:1/metrics",
                        "unknown",
                        "",
                        now,
                        "15s");
        var snapshot =
                new PrometheusAdapter.Snapshot(
                        true, "", now, Map.of("up", List.of(up)), List.of(one, two), Map.of());
        assertThat(
                        observations
                                .observe(
                                        ci,
                                        "j",
                                        snapshot,
                                        Map.of(
                                                "cpuUsage",
                                                new ObservabilityDtos.MetricEvidence(
                                                        1.0,
                                                        "%",
                                                        null,
                                                        now,
                                                        "OBSERVED",
                                                        "JVM_RUNTIME")),
                                        now,
                                        90,
                                        null,
                                        null)
                                .status())
                .isEqualTo("PARTIAL");
        var health =
                new NodeHealthService()
                        .compute(
                                "j",
                                "ACTIVE",
                                List.of(up),
                                List.of(),
                                Map.of("cpuUsage", 1),
                                now,
                                null,
                                null,
                                2);
        assertThat(health.health()).isEqualTo("UNKNOWN");
        assertThat(health.totalInstances()).isEqualTo(2);
    }

    @Test
    void incompleteRabbitContractIsPartialAndNativeBooleansDoNotSumAboveOne() {
        var snapshot =
                new PrometheusAdapter.Snapshot(
                        true,
                        "",
                        now,
                        Map.of(
                                "up",
                                List.of(
                                        new PrometheusAdapter.Sample(
                                                "opsagent-rabbitmq", "", 1, now)),
                                "raw",
                                List.of(
                                        sample(
                                                "rabbitmq_alarms_memory_used_watermark",
                                                "PROD",
                                                1,
                                                now),
                                        sample(
                                                "rabbitmq_alarms_memory_used_watermark",
                                                "PROD",
                                                1,
                                                now))));
        var evidence = observations.metricEvidence(snapshot, "5m", now, 90);
        assertThat(evidence.get("memoryAlarm").value()).isEqualTo(1);
        assertThat(evidence.get("memoryAlarm").aggregation())
                .isEqualTo("MAX_ACROSS_REPORTING_INSTANCES");
        assertThat(
                        observations
                                .observe(
                                        ci,
                                        "opsagent-rabbitmq",
                                        snapshot,
                                        Map.of(
                                                "connections",
                                                new ObservabilityDtos.MetricEvidence(
                                                        2.0,
                                                        "connections",
                                                        null,
                                                        now,
                                                        "OBSERVED",
                                                        "NATIVE_METRICS")),
                                        now,
                                        90,
                                        null,
                                        null)
                                .status())
                .isEqualTo("PARTIAL");
    }

    @Test
    void businessProbeCannotPairTheValueAndTimestampFromDifferentPublishers() {
        var result =
                new PrometheusAdapter.Sample(
                        "opsagent-platform",
                        "ops-demo-order-service",
                        1,
                        now,
                        Map.of("instance", "platform-one"),
                        null);
        var time =
                new PrometheusAdapter.Sample(
                        "opsagent-platform",
                        "ops-demo-order-service",
                        now.getEpochSecond(),
                        now,
                        Map.of("instance", "platform-two"),
                        null);
        var snapshot =
                new PrometheusAdapter.Snapshot(
                        true, "", now, Map.of("probe", List.of(result), "probeAt", List.of(time)));
        assertThat(observations.probe(snapshot, now, 90).value()).isNull();
        assertThat(
                        new NodeHealthService()
                                .compute(
                                        "j",
                                        "ACTIVE",
                                        List.of(sample("up", "PROD", 1, now)),
                                        List.of(),
                                        Map.of("rps", 2, "cpuUsage", 3),
                                        now,
                                        null,
                                        null,
                                        1,
                                        true)
                                .health())
                .isEqualTo("UNKNOWN");
    }

    private PrometheusAdapter.Sample sample(
            String metric, String environment, double value, Instant at) {
        return new PrometheusAdapter.Sample(
                "j",
                "",
                value,
                at,
                Map.of(
                        "__name__",
                        metric,
                        "job",
                        "j",
                        "environment",
                        environment,
                        "instance",
                        "one"),
                value == 1 ? at : null);
    }

    private PrometheusAdapter.Sample migrationSample(
            String metric, double value, Instant at, boolean explicit) {
        Map<String, String> labels =
                explicit
                        ? Map.of(
                                "__name__",
                                metric,
                                "job",
                                "j",
                                "instance",
                                "one",
                                "ci_code",
                                "rabbitmq",
                                "environment",
                                "PROD")
                        : Map.of("__name__", metric, "job", "j", "instance", "one");
        return new PrometheusAdapter.Sample("j", "", value, at, labels, value == 1 ? at : null);
    }
}
