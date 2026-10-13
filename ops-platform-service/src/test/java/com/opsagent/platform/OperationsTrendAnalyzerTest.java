package com.opsagent.platform;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证有样本约束的真实趋势估计，不将缺失、过期和低拟合度序列伪装成预测。
 *
 * @author heyu
 * @since 2026/9/3
 */
class OperationsTrendAnalyzerTest {
    private final OperationsTrendAnalyzer analyzer = new OperationsTrendAnalyzer();
    private final Instant now = Instant.parse("2026-09-03T10:00:00Z");

    @Test
    void shouldForecastLinearIncreaseAndExplainFifteenMinuteHorizon() {
        List<OperationsDtos.Point> points = new ArrayList<>();
        for (int i = 0; i <= 30; i++) points.add(new OperationsDtos.Point(now.minusSeconds((30L - i) * 60), 45 + i));
        var result = analyzer.analyze("heap", "heap", "opsagent-rag", points, 85, now);
        assertThat(result.currentValue()).isEqualTo(75);
        assertThat(result.forecastValue()).isEqualTo(90);
        assertThat(result.slopePerMinute()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("RISK");
        assertThat(result.reason()).contains("15分钟", "R²=1.00");
        assertThat(result.sampleCount()).isEqualTo(31);
    }

    @Test
    void shouldReturnUnknownForMissingOrExpiredSamples() {
        var empty = analyzer.analyze("heap", "heap", "service", List.of(), 85, now);
        assertThat(empty.status()).isEqualTo("UNKNOWN");
        assertThat(empty.currentValue()).isNull();
        var expired = analyzer.analyze("heap", "heap", "service",
                List.of(new OperationsDtos.Point(now.minusSeconds(600), 30)), 85, now);
        assertThat(expired.status()).isEqualTo("UNKNOWN");
        assertThat(expired.forecastValue()).isNull();
        assertThat(expired.reason()).contains("过期");
    }

    @Test
    void shouldNotForecastFromFewSamplesOrNoisyData() {
        var insufficient = analyzer.analyze("heap", "heap", "service", List.of(
                new OperationsDtos.Point(now.minusSeconds(60), 50), new OperationsDtos.Point(now, 60)), 85, now);
        assertThat(insufficient.currentValue()).isEqualTo(60);
        assertThat(insufficient.forecastValue()).isNull();
        List<OperationsDtos.Point> noisy = new ArrayList<>();
        for (int i = 0; i <= 30; i++) noisy.add(new OperationsDtos.Point(now.minusSeconds((30L - i) * 60),
                i % 2 == 0 ? 30 : 70));
        assertThat(analyzer.analyze("heap", "heap", "service", noisy, 85, now).forecastValue()).isNull();
    }

    @Test
    void shouldIgnoreInvalidSamplesAndStillFlagObservedThreshold() {
        var result = analyzer.analyze("heap", "heap", "service", List.of(
                new OperationsDtos.Point(now, Double.NaN), new OperationsDtos.Point(now, Double.POSITIVE_INFINITY),
                new OperationsDtos.Point(now, -1), new OperationsDtos.Point(now, 120),
                new OperationsDtos.Point(now, 90)), 85, now);
        assertThat(result.sampleCount()).isEqualTo(1);
        assertThat(result.currentValue()).isEqualTo(90);
        assertThat(result.status()).isEqualTo("RISK");
        assertThat(result.forecastValue()).isNull();
    }
}
