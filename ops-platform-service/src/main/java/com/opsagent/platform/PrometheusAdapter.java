package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * 固定 PromQL 的 Prometheus 适配器；不存在或过期的样本保留为缺失。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class PrometheusAdapter {
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Value("${ops.monitor.prometheus-url:http://localhost:9090}")
    private String baseUrl;

    @Value("${ops.observability.sample-max-age-seconds:90}")
    private long maximumSampleAge = 90;

    PrometheusAdapter(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @author heyu
     */
    record Sample(
            String job,
            String service,
            double value,
            Instant observedAt,
            Map<String, String> labels,
            Instant lastSuccessfulAt) {
        Sample(String job, String service, double value, Instant observedAt) {
            this(job, service, value, observedAt, Map.of(), value == 1 ? observedAt : null);
        }

        String label(String name) {
            return labels.getOrDefault(name, "");
        }
    }

    /**
     * @author heyu
     */
    record Target(
            Map<String, String> labels,
            String endpoint,
            String health,
            String error,
            Instant lastScrape,
            String interval) {
        String label(String name) {
            return labels.getOrDefault(name, "");
        }
    }

    /**
     * @author heyu
     */
    record Snapshot(
            boolean healthy,
            String message,
            Instant checkedAt,
            Map<String, List<Sample>> series,
            List<Target> targets,
            Map<String, String> errors) {
        Snapshot(
                boolean healthy,
                String message,
                Instant checkedAt,
                Map<String, List<Sample>> series) {
            this(
                    healthy,
                    message,
                    checkedAt,
                    series,
                    List.of(),
                    healthy ? Map.of() : Map.of("up", "SOURCE_UNAVAILABLE"));
        }
    }

    Snapshot collect(String range) {
        Instant now = Instant.now();
        Map<String, List<Sample>> data = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        boolean healthy = true;
        String message = "Prometheus 实时样本；请求指标为所选窗口平均速率";
        Map<String, String> queries = queries(range);
        for (var entry : queries.entrySet()) {
            try {
                data.put(entry.getKey(), query(entry.getValue()));
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                data.put(entry.getKey(), List.of());
                errors.put(entry.getKey(), "QUERY_UNAVAILABLE");
                healthy = false;
                message = "Prometheus 部分数据暂不可用，缺失项显示未知";
                if ("up".equals(entry.getKey())) break;
            }
        }
        List<Target> targets = List.of();
        if (!errors.containsKey("up")) {
            try {
                targets = targets();
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                errors.put("targets", "TARGETS_UNAVAILABLE");
                healthy = false;
            }
        }
        return new Snapshot(healthy, message, now, data, targets, errors);
    }

    long maximumSampleAge() {
        return Math.max(1, maximumSampleAge);
    }

    private List<Target> targets() throws Exception {
        JsonNode root = request("/api/v1/targets?state=active");
        if (!root.path("data").path("activeTargets").isArray()) {
            throw new IllegalStateException("Prometheus target response invalid");
        }
        List<Target> targets = new ArrayList<>();
        for (JsonNode item : root.path("data").path("activeTargets")) {
            targets.add(
                    new Target(
                            labels(item.path("labels")),
                            ObservabilitySanitizer.endpoint(item.path("scrapeUrl").asText()),
                            item.path("health").asText("unknown"),
                            ObservabilitySanitizer.summary(item.path("lastError").asText()),
                            instant(item.path("lastScrape").asText()),
                            item.path("scrapeInterval").asText()));
        }
        return List.copyOf(targets);
    }

    private Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, String> labels(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key :
                List.of(
                        "__name__",
                        "job",
                        "service",
                        "ci_code",
                        "environment",
                        "namespace",
                        "cluster",
                        "instance",
                        "name")) {
            if (node.has(key))
                result.put(key, ObservabilitySanitizer.summary(node.path(key).asText()));
        }
        return Map.copyOf(result);
    }

    private JsonNode request(String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
        var future = http.sendAsync(request, ignored -> new LimitedBody());
        try {
            var response = future.get(4, TimeUnit.SECONDS);
            if (response.statusCode() != 200)
                throw new IllegalStateException("Prometheus HTTP unavailable");
            JsonNode root = json.readTree(response.body());
            if (!"success".equals(root.path("status").asText())) {
                throw new IllegalStateException("Prometheus query unavailable");
            }
            return root;
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    /**
     * @author heyu
     */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            size += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (size > 4 * 1024 * 1024) {
                subscription.cancel();
                delegate.onError(new IllegalStateException("Prometheus response too large"));
            } else {
                delegate.onNext(buffers);
            }
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }

    List<Sample> query(String query) throws Exception {
        JsonNode root =
                request("/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        List<Sample> result = new ArrayList<>();
        for (JsonNode item : root.path("data").path("result")) {
            JsonNode values = item.path("values");
            JsonNode value =
                    item.has("values") ? values.path(values.size() - 1) : item.path("value");
            try {
                if (!value.isArray() || value.size() != 2 || !value.path(0).isNumber()) continue;
                double number = Double.parseDouble(value.path(1).asText());
                double timestamp = value.path(0).asDouble();
                if (!Double.isFinite(number) || !Double.isFinite(timestamp) || timestamp <= 0)
                    continue;
                Instant successful = null;
                if (query.startsWith("up[")) {
                    for (JsonNode point : values) {
                        if ("1".equals(point.path(1).asText())) {
                            successful =
                                    Instant.ofEpochMilli((long) (point.path(0).asDouble() * 1000));
                        }
                    }
                }
                result.add(
                        new Sample(
                                item.path("metric").path("job").asText(),
                                item.path("metric").path("service").asText(),
                                number,
                                Instant.ofEpochMilli((long) (timestamp * 1000)),
                                labels(item.path("metric")),
                                successful));
            } catch (RuntimeException ignored) {
                // An invalid individual sample cannot establish health or zero traffic.
            }
        }
        return result;
    }

    private Map<String, String> queries(String range) {
        String group = "job,ci_code,environment,namespace,cluster";
        String rate =
                "sum by(" + group + ")(rate(http_server_requests_seconds_count[" + range + "]))";
        Map<String, String> queries = new LinkedHashMap<>();
        // Instant-vector timestamps describe evaluation time, even for a lookback sample.
        // A range selector preserves raw scrape times; the consumer still enforces freshness.
        queries.put("up", "up[" + Math.max(1, maximumSampleAge) + "s]");
        queries.put("rps", rate);
        queries.put(
                "errorRate",
                "100 * (sum by("
                        + group
                        + ")(rate(http_server_requests_seconds_count"
                        + "{status=~\"5..\"}["
                        + range
                        + "])) or on("
                        + group
                        + ") (0 * "
                        + rate
                        + ")) / ("
                        + rate
                        + " > 0)");
        queries.put(
                "p95Ms",
                "1000 * histogram_quantile(0.95, sum by("
                        + group
                        + ",le)"
                        + "(rate(http_server_requests_seconds_bucket["
                        + range
                        + "])))");
        queries.put("cpuUsage", "100 * avg by(" + group + ")(process_cpu_usage)");
        queries.put(
                "memoryUsage",
                "100 * sum by("
                        + group
                        + ")(jvm_memory_used_bytes{area=\"heap\"})"
                        + " / (sum by("
                        + group
                        + ")(jvm_memory_max_bytes{area=\"heap\"}) > 0)");
        queries.put("probe", "opsagent_demo_probe_success{job=\"opsagent-platform\"}");
        queries.put("probeAt", "opsagent_demo_probe_timestamp_seconds{job=\"opsagent-platform\"}");
        queries.put(
                "raw",
                "{__name__=~\"http_server_requests_seconds_(count|bucket)|process_cpu_usage|"
                    + "jvm_memory_(used|max)_bytes|rabbitmq_(connections|channel_consumers|queue_messages_ready|"
                    + "queue_messages_unacked|alarms_.*)|nacos_monitor|collections_total|collections_vector_total|"
                    + "app_status_recovery_mode|memory_resident_bytes|prometheus_tsdb_head_series|"
                    + "alertmanager_alerts|grafana_stat_totals_instance|opsagent_infra_.*\"}["
                        + maximumSampleAge()
                        + "s]");
        return queries;
    }
}
