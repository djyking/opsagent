package com.opsagent.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 观测绑定和布局请求；绑定只能选取标签，不接受任意 URL 或 PromQL。
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class ObservabilityDtos {
    private ObservabilityDtos() {}

    /**
     * @author heyu
     */
    public record Identity(
            String ciCode,
            String environment,
            String namespace,
            String cluster,
            String sourceInstanceId) {}

    /**
     * @author heyu
     */
    public record ObservationCheck(String step, String status, String reasonCode, String message) {}

    /**
     * @author heyu
     */
    public record ObservedInstance(
            Identity identity,
            String job,
            String instance,
            String endpoint,
            String health,
            String lastError,
            Instant lastScrape,
            Instant sampledAt,
            Instant lastSuccessfulScrapeAt,
            Double up,
            String source) {}

    /**
     * @author heyu
     */
    public record Observation(
            String status,
            String reasonCode,
            String message,
            Instant sampledAt,
            Instant fetchedAt,
            Instant lastSuccessfulScrapeAt,
            long maximumSampleAgeSeconds,
            List<ObservationCheck> checks,
            List<ObservedInstance> instances) {}

    /**
     * @author heyu
     */
    public record MetricEvidence(
            Double value,
            String unit,
            Integer windowSeconds,
            Instant sampledAt,
            String reasonCode,
            String scope,
            String aggregation) {
        public MetricEvidence(
                Double value,
                String unit,
                Integer windowSeconds,
                Instant sampledAt,
                String reasonCode,
                String scope) {
            this(
                    value,
                    unit,
                    windowSeconds,
                    sampledAt,
                    reasonCode,
                    scope,
                    "PROMQL_SCOPED_AGGREGATION");
        }
    }

    /**
     * @author heyu
     */
    public record Bindings(
            @Pattern(regexp = "[a-zA-Z0-9_.:-]{0,128}") String prometheusJob,
            @Pattern(regexp = "[a-zA-Z0-9_.:-]{0,128}") String sentinelApp,
            @Pattern(regexp = "[a-zA-Z0-9_.:-]{0,128}") String nacosDataId,
            @Pattern(regexp = "[a-zA-Z0-9_.:-]{0,128}") String alertLabel) {}

    /**
     * @author heyu
     */
    public record Position(@NotBlank @Size(max = 64) String ciCode, double x, double y) {}

    /**
     * @author heyu
     */
    public record Layout(
            @NotBlank @Pattern(regexp = "[A-Z0-9_-]{1,32}") String environment,
            @NotNull @Size(max = 500) List<@Valid Position> positions) {}
}
