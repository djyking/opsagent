package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Separate real zero, missing/stale counters and independent gateway/question scopes.
 *
 * @author heyu
 * @since 2026/9/3
 */
class TrafficObservationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    @Test
    void validZeroIsKeptAndHttpTrafficDoesNotInventApiOrQuestionTraffic() {
        var readings = readings();
        readings.put("gatewayRate", new TrafficObservationService.Reading(1.17, false));
        readings.put("gatewayCount", new TrafficObservationService.Reading(351.0, false));
        var result = TrafficObservationService.project(readings, NOW, 90);
        assertThat(result.windowSeconds()).isEqualTo(300);
        assertThat(result.streams()).hasSize(2);
        var gateway = result.streams().get(0);
        assertThat(gateway.status()).isEqualTo("AVAILABLE");
        assertThat(gateway.requestsPerSecond()).isEqualTo(1.17);
        assertThat(gateway.apiRequestsPerSecond()).isZero();
        assertThat(gateway.scope()).contains("健康检查", "静态资源");
        var question = result.streams().get(1);
        assertThat(question.requestsPerSecond()).isZero();
        assertThat(question.requestCount()).isZero();
        assertThat(question.blockedCount()).isZero();
        assertThat(question.scope()).contains("不等同于模型调用次数");
    }

    @Test
    void missingCounterOrInsufficientRateSamplesAreUnknownNotZero() {
        var readings = readings();
        readings.put("gatewayRate", new TrafficObservationService.Reading(null, false));
        readings.put("gatewayCount", new TrafficObservationService.Reading(null, false));
        var gateway = TrafficObservationService.project(readings, NOW, 90).streams().get(0);
        assertThat(gateway.status()).isEqualTo("NO_SAMPLES");
        assertThat(gateway.requestsPerSecond()).isNull();
        assertThat(gateway.requestCount()).isNull();
    }

    @Test
    void staleAndFutureSamplesCannotMakeHistoricalTrafficLookCurrent() {
        for (long offset : List.of(-91L, 60L)) {
            var readings = readings();
            readings.put(
                    "gatewaySampledAt",
                    new TrafficObservationService.Reading(
                            (double) NOW.plusSeconds(offset).getEpochSecond(), false));
            readings.put("gatewayRate", new TrafficObservationService.Reading(8.0, false));
            var gateway = TrafficObservationService.project(readings, NOW, 90).streams().get(0);
            assertThat(gateway.status()).isEqualTo("NO_SAMPLES");
            assertThat(gateway.requestsPerSecond()).isNull();
            assertThat(gateway.apiRequestsPerSecond()).isNull();
        }
    }

    @Test
    void partialFailurePreservesIndependentKnownMetrics() {
        var readings = readings();
        readings.put("gatewayRate", new TrafficObservationService.Reading(0.002, false));
        readings.put("gatewayCount", new TrafficObservationService.Reading(null, true));
        var result = TrafficObservationService.project(readings, NOW, 90);
        assertThat(result.streams().get(0).status()).isEqualTo("PARTIAL");
        assertThat(result.streams().get(0).requestsPerSecond()).isEqualTo(0.002);
        assertThat(result.streams().get(0).requestCount()).isNull();
        assertThat(result.streams().get(1).status()).isEqualTo("AVAILABLE");
    }

    @Test
    void unavailableSourceIsBoundedCachedAndDoesNotFillExampleNumbers() throws Exception {
        var prometheus = mock(PrometheusAdapter.class);
        when(prometheus.maximumSampleAge()).thenReturn(90L);
        when(prometheus.query(anyString()))
                .thenThrow(new IllegalStateException("query unavailable"));
        var service = new TrafficObservationService(prometheus);
        var first = service.overview();
        assertThat(service.overview()).isSameAs(first);
        assertThat(first.streams())
                .allSatisfy(
                        stream -> {
                            assertThat(stream.status()).isEqualTo("UNAVAILABLE");
                            assertThat(stream.requestsPerSecond()).isNull();
                            assertThat(stream.requestCount()).isNull();
                        });
        verify(prometheus, times(8)).query(anyString());
    }

    private Map<String, TrafficObservationService.Reading> readings() {
        Map<String, TrafficObservationService.Reading> result = new LinkedHashMap<>();
        for (String key : List.of("gateway", "question")) {
            result.put(key + "Rate", new TrafficObservationService.Reading(0.0, false));
            result.put(key + "Count", new TrafficObservationService.Reading(0.0, false));
            result.put(
                    key + "SampledAt",
                    new TrafficObservationService.Reading(
                            (double) NOW.minusSeconds(10).getEpochSecond(), false));
        }
        result.put("gatewayApiRate", new TrafficObservationService.Reading(0.0, false));
        result.put("questionBlocked", new TrafficObservationService.Reading(0.0, false));
        return result;
    }
}
