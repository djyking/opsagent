package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汇总 Prometheus 抓取目标与 Grafana 健康状态，供监控工作台使用。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class MonitoringService {
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Value("${ops.monitor.prometheus-url:http://localhost:9090}")
    private String prometheusUrl;

    @Value("${ops.monitor.grafana-url:http://localhost:3000}")
    private String grafanaUrl;

    MonitoringService(ObjectMapper json) {
        this.json = json;
    }

    Map<String, Object> summary() {
        List<Map<String, Object>> services = new ArrayList<>();
        String prometheusError = null;
        try {
            JsonNode targets = get(prometheusUrl + "/api/v1/targets");
            for (JsonNode target : targets.path("data").path("activeTargets")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("job", target.path("labels").path("job").asText("unknown"));
                item.put("health", target.path("health").asText("unknown"));
                item.put("lastScrape", target.path("lastScrape").asText(""));
                item.put("lastError", target.path("lastError").asText(""));
                item.put("scrapeUrl", target.path("scrapeUrl").asText(""));
                services.add(item);
            }
        } catch (Exception exception) {
            prometheusError = exception.getMessage();
        }

        Map<String, Object> grafana = new LinkedHashMap<>();
        grafana.put("url", grafanaUrl);
        grafana.put("dashboardUrl", grafanaUrl + "/d/opsagent-overview/opsagent-overview");
        try {
            JsonNode health = get(grafanaUrl + "/api/health");
            grafana.put("healthy", "ok".equalsIgnoreCase(health.path("database").asText()));
            grafana.put("version", health.path("version").asText("unknown"));
        } catch (Exception exception) {
            grafana.put("healthy", false);
            grafana.put("error", exception.getMessage());
        }

        long up = services.stream().filter(item -> "up".equals(item.get("health"))).count();
        Map<String, Object> prometheus = new LinkedHashMap<>();
        prometheus.put("url", prometheusUrl);
        prometheus.put("targetsUrl", prometheusUrl + "/targets");
        prometheus.put("healthy", prometheusError == null);
        prometheus.put("targetCount", services.size());
        prometheus.put("upCount", up);
        if (prometheusError != null) prometheus.put("error", prometheusError);

        return Map.of(
                "checkedAt", Instant.now(),
                "services", services,
                "prometheus", prometheus,
                "grafana", grafana);
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }
}
