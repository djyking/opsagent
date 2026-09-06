package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One shared ten-second snapshot, without forwarding Nacos identities to application services.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class SentinelTrafficClient {
    record Snapshot(
            String status,
            List<TrafficGovernanceDtos.Resource> resources,
            JsonNode rules,
            Instant observedAt) {}

    private final ObjectMapper json;
    private final HttpClient http;

    @Value("${ops.operations.rag-url:http://localhost:8104}")
    private String url;

    private Snapshot cached;
    private Instant cacheUntil = Instant.EPOCH;

    @Autowired
    SentinelTrafficClient(ObjectMapper json) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    SentinelTrafficClient(ObjectMapper json, HttpClient http) {
        this.json = json;
        this.http = http;
    }

    synchronized void invalidate() {
        cacheUntil = Instant.EPOCH;
    }

    synchronized Snapshot snapshot() {
        if (cached != null && Instant.now().isBefore(cacheUntil)) return cached;
        try {
            var request =
                    HttpRequest.newBuilder(URI.create(url + "/api/rag/runtime/traffic"))
                            .timeout(Duration.ofSeconds(4))
                            .GET();
            if (RequestContextHolder.getRequestAttributes()
                    instanceof ServletRequestAttributes servlet) {
                String auth = servlet.getRequest().getHeader("Authorization");
                if (auth != null && auth.startsWith("Bearer "))
                    request.header("Authorization", auth);
            }
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 262144)
                throw new IllegalStateException();
            JsonNode body = json.readTree(response.body());
            if (body.path("code").asInt(-1) != 0) throw new IllegalStateException();
            JsonNode data = body.path("data");
            String status = data.path("status").asText();
            if (!List.of("AVAILABLE", "DISABLED").contains(status)
                    || !data.path("resources").isArray()
                    || !data.path("rules").isObject()) throw new IllegalStateException();
            List<TrafficGovernanceDtos.Resource> resources = new ArrayList<>();
            for (JsonNode item : data.path("resources")) {
                String resource = item.path("resource").asText();
                if (!TrafficRuleValidator.RESOURCES.contains(resource)) continue;
                resources.add(
                        new TrafficGovernanceDtos.Resource(
                                resource,
                                resource.equals("ops-rag-request") ? "问答完整请求" : "问答入口校验",
                                "AVAILABLE".equals(item.path("status").asText())
                                        ? "AVAILABLE"
                                        : "NO_SAMPLES",
                                number(item, "passQps"),
                                number(item, "blockQps"),
                                number(item, "avgRt"),
                                item.path("activeThreads").isIntegralNumber()
                                        ? item.path("activeThreads").asInt()
                                        : null,
                                resource.equals("ops-rag-request")
                                        ? "包含同步与流式生命周期"
                                        : "仅入口校验，不代表模型耗时"));
            }
            cached =
                    new Snapshot(
                            status,
                            List.copyOf(resources),
                            data.path("rules").deepCopy(),
                            Instant.now());
        } catch (Exception ignored) {
            cached = new Snapshot("UNAVAILABLE", List.of(), json.createObjectNode(), Instant.now());
        }
        cacheUntil = Instant.now().plusSeconds(10);
        return cached;
    }

    private static Double number(JsonNode value, String field) {
        return value.path(field).isNumber()
                        && Double.isFinite(value.path(field).asDouble())
                        && value.path(field).asDouble() >= 0
                ? value.path(field).asDouble()
                : null;
    }
}
