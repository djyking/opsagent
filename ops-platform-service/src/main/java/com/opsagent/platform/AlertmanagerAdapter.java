package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 仅投影服务告警摘要，不回传任意标签、生成器 URL 或内部请求信息。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class AlertmanagerAdapter {
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Value("${ops.observability.alertmanager-url:http://localhost:9093}")
    private String baseUrl;

    AlertmanagerAdapter(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @author heyu
     */
    record Alert(
            String id,
            String title,
            String severity,
            String summary,
            String startsAt,
            String ciCode,
            String job,
            String status,
            String environment,
            String namespace,
            String cluster) {
        Alert(
                String id,
                String title,
                String severity,
                String summary,
                String startsAt,
                String ciCode,
                String job,
                String status) {
            this(id, title, severity, summary, startsAt, ciCode, job, status, "", "", "");
        }

        Map<String, Object> view() {
            return Map.of(
                    "id",
                    id,
                    "title",
                    title,
                    "severity",
                    severity,
                    "summary",
                    summary,
                    "startsAt",
                    startsAt,
                    "ciCode",
                    ciCode,
                    "status",
                    status,
                    "environment",
                    environment,
                    "namespace",
                    namespace,
                    "cluster",
                    cluster);
        }
    }

    /**
     * @author heyu
     */
    record Snapshot(boolean healthy, String message, Instant checkedAt, List<Alert> alerts) {}

    Snapshot collect() {
        Instant now = Instant.now();
        try {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            baseUrl
                                                    + "/api/v2/alerts?active=true&silenced=false&inhibited=false"))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException("Alertmanager unavailable");
            JsonNode body = json.readTree(response.body());
            if (!body.isArray()) throw new IllegalStateException("Alertmanager invalid response");
            List<Alert> alerts = new ArrayList<>();
            for (JsonNode row : body) {
                JsonNode labels = row.path("labels");
                String state = row.path("status").path("state").asText();
                if (!"active".equals(state)) continue;
                String ci =
                        labels.path("service_ci_code")
                                .asText(
                                        labels.path("ci_code")
                                                .asText(labels.path("service").asText()));
                alerts.add(
                        new Alert(
                                row.path("fingerprint").asText(),
                                ObservabilitySanitizer.summary(labels.path("alertname").asText()),
                                labels.path("severity")
                                        .asText("unknown")
                                        .toUpperCase(java.util.Locale.ROOT),
                                ObservabilitySanitizer.summary(
                                        row.path("annotations").path("summary").asText()),
                                row.path("startsAt").asText(),
                                ci,
                                labels.path("job").asText(),
                                "FIRING",
                                labels.path("environment")
                                        .asText()
                                        .replace("ISOLATED_DEMO", "DEMO"),
                                labels.path("namespace").asText(),
                                labels.path("cluster").asText()));
            }
            return new Snapshot(true, "Alertmanager 当前活动告警", now, List.copyOf(alerts));
        } catch (Exception exception) {
            return new Snapshot(false, "Alertmanager 数据暂不可用，不能据此判断零告警", now, List.of());
        }
    }
}
