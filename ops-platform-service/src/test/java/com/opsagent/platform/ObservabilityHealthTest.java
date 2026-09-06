package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 验证采集缺失、过期、多实例、业务探针和告警的健康优先级。
 *
 * @author heyu
 * @since 2026/9/3
 */
class ObservabilityHealthTest {
    private final NodeHealthService health = new NodeHealthService();
    private final Instant now = Instant.now();

    @Test
    void missingStaleAndFutureSamplesCannotEstablishHealth() {
        assertThat(state(List.of(), List.of(), Map.of()).health()).isEqualTo("UNKNOWN");
        assertThat(state(List.of(up(1, now.minusSeconds(100))), List.of(), Map.of()).health())
                .isEqualTo("UNKNOWN");
        assertThat(state(List.of(up(1, now.plusSeconds(60))), List.of(), Map.of()).health())
                .isEqualTo("UNKNOWN");
        assertThat(state(List.of(up(1, now)), List.of(), Map.of()).health()).isEqualTo("UNKNOWN");
        assertThat(state(List.of(up(1, now)), List.of(), Map.of("cpuUsage", 3)).health())
                .isEqualTo("HEALTHY");
    }

    @Test
    void partialAvailabilityAndRedThresholdsDegrade() {
        var partial = state(List.of(up(1, now), up(0, now)), List.of(), Map.of());
        assertThat(partial.health()).isEqualTo("UNKNOWN");
        assertThat(partial.healthyInstances()).isEqualTo(1);
        assertThat(partial.totalInstances()).isEqualTo(2);
        assertThat(state(List.of(up(0, now)), List.of(), Map.of()).health()).isEqualTo("UNKNOWN");
        assertThat(state(List.of(up(1, now)), List.of(), Map.of("p95Ms", 1500)).health())
                .isEqualTo("DEGRADED");
        assertThat(state(List.of(up(1, now)), List.of(), Map.of("errorRate", 7)).health())
                .isEqualTo("DEGRADED");
    }

    @Test
    void independentCriticalAlertSurvivesPrometheusOutage() {
        var alert =
                new AlertmanagerAdapter.Alert(
                        "a", "Latency", "CRITICAL", "latency", "", "svc", "j", "FIRING");
        assertThat(state(List.of(), List.of(alert), Map.of()).health()).isEqualTo("CRITICAL");
    }

    @Test
    void freshBusinessFailureOverridesHealthyJvmButExpiredSuccessDoesNot() {
        assertThat(
                        health.compute(
                                        "j",
                                        "ACTIVE",
                                        List.of(up(1, now)),
                                        List.of(),
                                        Map.of(),
                                        now,
                                        0.0,
                                        now)
                                .health())
                .isEqualTo("CRITICAL");
        assertThat(
                        health.compute(
                                        "j",
                                        "ACTIVE",
                                        List.of(up(1, now)),
                                        List.of(),
                                        Map.of(),
                                        now,
                                        1.0,
                                        now.minusSeconds(60))
                                .health())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void metadataIsNotHealthAndSecretsAreNotShownInUrls() {
        assertThat(
                        health.compute(
                                        "",
                                        "DISABLED",
                                        List.of(),
                                        List.of(),
                                        Map.of(),
                                        now,
                                        null,
                                        null)
                                .health())
                .isEqualTo("UNKNOWN");
        assertThat(
                        ObservabilitySanitizer.endpoint(
                                "https://user:secret@host/path?token=value#fragment"))
                .isEqualTo("https://******@host/path");
        assertThat(ObservabilitySanitizer.summary("token=abc password: xyz"))
                .doesNotContain("abc", "xyz");
    }

    private NodeHealthService.Health state(
            List<PrometheusAdapter.Sample> up,
            List<AlertmanagerAdapter.Alert> alerts,
            Map<String, Object> metrics) {
        return health.compute("j", "ACTIVE", up, alerts, metrics, now, null, null);
    }

    private PrometheusAdapter.Sample up(double value, Instant at) {
        return new PrometheusAdapter.Sample("j", "", value, at);
    }
}
