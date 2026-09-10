package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Completion-based residence, same-window coalescing and independent window progress.
 *
 * @author heyu
 * @since 2026/9/3
 */
class ObservationSnapshotCacheTest {
    @Test
    void cacheResidenceBeginsAfterSlowLoadWithoutRewritingSourceTime() {
        AtomicLong clock = new AtomicLong();
        var cache =
                new ObservationSnapshotCache<PrometheusAdapter.Snapshot>(
                        Duration.ofSeconds(10), clock::get);
        var source = new PrometheusAdapter.Snapshot(true, "", Instant.EPOCH, Map.of());
        AtomicInteger calls = new AtomicInteger();
        var first =
                cache.get(
                        "15m",
                        () -> {
                            calls.incrementAndGet();
                            clock.set(TimeUnit.SECONDS.toNanos(20));
                            return source;
                        });
        clock.set(TimeUnit.SECONDS.toNanos(29));
        assertThat(
                        cache.get(
                                "15m",
                                () -> {
                                    calls.incrementAndGet();
                                    return source;
                                }))
                .isSameAs(first);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(first.checkedAt()).isEqualTo(Instant.EPOCH);
        clock.set(TimeUnit.SECONDS.toNanos(31));
        cache.get(
                "15m",
                () -> {
                    calls.incrementAndGet();
                    return source;
                });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void sameWindowCoalescesWhileDifferentWindowAndAlertsContinue() throws Exception {
        var prometheus = mock(PrometheusAdapter.class);
        var alertmanager = mock(AlertmanagerAdapter.class);
        var cmdb = mock(ItsmPlatformService.class);
        var repository = mock(ObservabilityRepository.class);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var service =
                new TopologyAggregationService(
                        cmdb,
                        prometheus,
                        alertmanager,
                        new NodeHealthService(),
                        repository,
                        mock(PlatformAuditRepository.class),
                        json);
        var slowStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger slowCalls = new AtomicInteger();
        var sample = new PrometheusAdapter.Snapshot(true, "", Instant.EPOCH, Map.of());
        when(prometheus.collect("15m"))
                .thenAnswer(
                        invocation -> {
                            slowCalls.incrementAndGet();
                            slowStarted.countDown();
                            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
                            return sample;
                        });
        when(prometheus.collect("5m")).thenReturn(sample);
        when(alertmanager.collect())
                .thenReturn(new AlertmanagerAdapter.Snapshot(true, "", Instant.EPOCH, List.of()));
        when(cmdb.cis(null, null)).thenReturn(List.of());
        when(cmdb.relations()).thenReturn(List.of());
        var executor = Executors.newFixedThreadPool(3);
        try {
            var first = executor.submit(() -> service.metrics("15m"));
            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var same = executor.submit(() -> service.metrics("15m"));
            var different = executor.submit(() -> service.topology("ALL", "5m", "CONFIGURED"));
            assertThat(different.get(1, TimeUnit.SECONDS).get("nodes")).isEqualTo(List.of());
            assertThat(first.isDone()).isFalse();
            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isSameAs(sample);
            assertThat(same.get(1, TimeUnit.SECONDS)).isSameAs(sample);
            assertThat(slowCalls.get()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedLoaderReleasesItsFlightSoTheNextReadCanRecover() {
        var cache = new ObservationSnapshotCache<String>(Duration.ofSeconds(10));
        assertThatThrownBy(
                        () ->
                                cache.get(
                                        "5m",
                                        () -> {
                                            throw new IllegalStateException("down");
                                        }))
                .hasMessage("down");
        assertThat(cache.get("5m", () -> "ready")).isEqualTo("ready");
    }
}
