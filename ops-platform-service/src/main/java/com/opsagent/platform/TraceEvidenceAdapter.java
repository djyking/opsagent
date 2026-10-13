package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

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
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * 固定 Tempo/Prometheus 只读适配器；服务图统计和保留的 Trace 明确区分。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class TraceEvidenceAdapter {
    private final boolean enabled;
    private final String tempo;
    private final String prometheus;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, Graph> cache = new HashMap<>();

    TraceEvidenceAdapter(
            @Value("${OPS_TRACE_ENABLED:false}") boolean enabled,
            @Value("${OPS_TEMPO_URL:http://tempo:3200}") String tempo,
            @Value("${ops.monitor.prometheus-url:http://localhost:9090}") String prometheus,
            ObjectMapper json) {
        this.enabled = enabled;
        this.tempo = base(tempo);
        this.prometheus = base(prometheus);
        this.json = json;
    }

    static long seconds(String range) {
        return switch (range) {
            case "5m" -> 300;
            case "15m" -> 900;
            case "30m" -> 1800;
            case "1h" -> 3600;
            case "6h" -> 21600;
            default -> throw invalid("不支持的观测时间窗");
        };
    }

    static String environment(String environment) {
        if (!Set.of("ALL", "PROD", "DEMO", "STAGING", "TEST", "DEV").contains(environment)) {
            throw invalid("不支持的服务环境");
        }
        return environment;
    }

    static String runtimeCode(String code) {
        return "ops-demo-notification-service".equals(code) ? "ops-demo-order-service" : code;
    }

    synchronized Graph graph(String range) {
        long window = seconds(range);
        Instant now = Instant.now();
        if (!enabled) return new Graph("NOT_CONFIGURED", "Trace 采集未启用", now, List.of());
        Graph old = cache.get(range);
        if (old != null && old.fetchedAt().isAfter(now.minusSeconds(15))) return old;
        Graph result;
        try {
            JsonNode target = query("up{job=\"opsagent-servicegraph\"}[90s]");
            boolean fresh = false;
            for (JsonNode row : target) {
                JsonNode points = row.path("values");
                if (!points.isEmpty()) {
                    JsonNode point = points.get(points.size() - 1);
                    if (point.get(1).asDouble() == 1
                            && point.get(0).asDouble() > now.getEpochSecond() - 90) fresh = true;
                }
            }
            if (!fresh) {
                result = new Graph("FAILED", "服务图采集目标缺少新鲜成功样本，关系差异无法确定", now, List.of());
                cache.put(range, result);
                return result;
            }
            String labels =
                    "client,server,connection_type,virtual_node,client_messaging_system,server_messaging_system";
            JsonNode counts =
                    query(
                            "sum by ("
                                    + labels
                                    + ") (increase(traces_service_graph_request_total["
                                    + range
                                    + "]))");
            JsonNode failures =
                    query(
                            "sum by ("
                                    + labels
                                    + ") (increase(traces_service_graph_request_failed_total["
                                    + range
                                    + "]))");
            Map<String, Double> errors = new HashMap<>();
            failures.forEach(row -> errors.put(pairKey(row.path("metric")), number(row)));
            List<Map<String, Object>> edges = new ArrayList<>();
            for (JsonNode row : counts) {
                Double count = number(row);
                if (count == null || count <= 0 || edges.size() >= 500) continue;
                JsonNode metric = row.path("metric");
                String source = metric.path("client").asText();
                String targetName = metric.path("server").asText();
                String kind = metric.path("connection_type").asText();
                String relation = observedRelation(metric);
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("id", id(source + ":" + targetName + ":" + kind));
                edge.put("edgeId", edge.get("id"));
                edge.put("sourceService", source);
                edge.put("targetService", targetName);
                edge.put("relationType", relation);
                edge.put("relationSource", "OBSERVED");
                edge.put("connectionType", kind);
                edge.put(
                        "messagingSystem",
                        metric.path("client_messaging_system")
                                .asText(metric.path("server_messaging_system").asText()));
                edge.put("virtualPeer", !metric.path("virtual_node").asText().isBlank());
                edge.put("windowStart", now.minusSeconds(window).toString());
                edge.put("windowEnd", now.toString());
                edge.put("firstSeenAt", null);
                edge.put("lastSeenAt", null);
                edge.put("sampledRequests", count);
                edge.put("rps", count / window);
                Double failed = errors.get(pairKey(metric));
                edge.put("errorRate", failed == null ? null : Math.min(100, failed * 100 / count));
                edge.put("p95Ms", null);
                edge.put("metricsScope", "SAMPLED_TRACE_PAIRS");
                edge.put("quality", "AGGREGATED_SAMPLED_SPANS");
                edge.put(
                        "confidenceBasis",
                        !metric.path("virtual_node").asText().isBlank()
                                ? "仅已采集一端与未埋点peer的证据，不证明broker或服务器健康；采样量不代表全量流量"
                                : "Collector 对 client/server 或 producer/consumer"
                                        + " 配对；采样请求数不代表全量HTTP流量，时间表示聚合窗口");
                edge.put(
                        "evidenceRefs",
                        List.of("servicegraph:" + edge.get("id") + ":" + now.getEpochSecond()));
                edges.add(edge);
            }
            result =
                    new Graph(
                            edges.isEmpty() ? "NO_DATA" : "READY",
                            edges.isEmpty()
                                    ? "采集可用，本窗口没有可配对调用增量；不表示依赖不存在"
                                    : "真实采样调用关系，完整HTTP流量见服务指标",
                            now,
                            edges);
        } catch (RuntimeException failure) {
            result = new Graph("FAILED", "服务图指标暂不可用；未将缺数据解释为关系消失", now, List.of());
        }
        cache.put(range, result);
        return result;
    }

    Map<String, Object> search(String code, String environment, String range, String targetCode) {
        return search(code, environment, range, targetCode, deadline());
    }

    static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    }

    Map<String, Object> search(
            String code, String environment, String range, String targetCode, long deadline) {
        environment(environment);
        Instant end = Instant.now();
        Instant start = end.minusSeconds(seconds(range));
        if (!enabled)
            return Map.of("status", "NOT_CONFIGURED", "message", "Trace 未启用", "items", List.of());
        String scope = "resource.service.namespace = \"opsagent\"";
        if (!environment.equals("ALL"))
            scope += " && resource.opsagent.environment = \"" + environment + "\"";
        if (code != null && !code.isBlank()) {
            if (!code.matches("[a-zA-Z0-9_-]{1,64}")) throw invalid("无效服务标识");
            scope += " && resource.opsagent.ci.code = \"" + runtimeCode(code) + "\"";
        }
        boolean directed = targetCode != null && !targetCode.isBlank();
        if (directed && !targetCode.matches("[a-zA-Z0-9_-]{1,64}")) throw invalid("无效目标服务标识");
        if (directed && targetCode.equals(code)) scope += " && span:kind = producer";
        try {
            JsonNode result =
                    read(
                            tempo
                                    + "/api/search?q="
                                    + encode("{ " + scope + " }")
                                    + "&start="
                                    + start.getEpochSecond()
                                    + "&end="
                                    + end.getEpochSecond()
                                    + "&limit="
                                    + (directed ? 5 : 20),
                            deadline);
            List<Map<String, Object>> items = new ArrayList<>();
            int examined = 0;
            boolean incomplete = false;
            for (JsonNode trace : result.path("traces")) {
                if (++examined > (directed ? 5 : 20) || System.nanoTime() >= deadline) {
                    incomplete = true;
                    break;
                }
                String traceId =
                        traceIdentifier(
                                trace.path("traceID").asText(trace.path("traceId").asText()));
                if (traceId.isBlank()) continue;
                if (directed) {
                    Map<String, Object> detail;
                    try {
                        detail = trace(traceId, environment, code, deadline);
                    } catch (RuntimeException unavailable) {
                        incomplete = true;
                        continue;
                    }
                    if (!hasDirectedRelation(rows(detail.get("spans")), code, targetCode)) continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("traceId", traceId);
                item.put("startTime", nanoTime(trace.path("startTimeUnixNano")).toString());
                item.put("durationMs", trace.path("durationMs").asDouble());
                item.put("rootService", safeService(trace.path("rootServiceName").asText()));
                item.put("serviceCount", null);
                item.put("source", "TEMPO");
                items.add(item);
                if (items.size() >= (directed ? 5 : 20)) break;
            }
            return Map.of(
                    "status",
                    items.isEmpty() ? incomplete ? "FAILED" : "NO_DATA" : "READY",
                    "message",
                    items.isEmpty()
                            ? incomplete
                                    ? "Trace 查询未全部完成，请稍后重试；不能据此判断关系不存在"
                                    : directed
                                            ? "有界采样候选中未找到该方向的直接调用证据；不能据此判断关系不存在"
                                            : "本窗口未保留可下钻Trace，采样和保留范围不同于聚合流量"
                            : directed ? "最多核对5条保留Trace，仅展示父子调用或明确peer方向证据" : "实际保留的采样Trace",
                    "windowStart",
                    start,
                    "windowEnd",
                    end,
                    "items",
                    items);
        } catch (BusinessException denied) {
            throw denied;
        } catch (RuntimeException failure) {
            return Map.of("status", "FAILED", "message", "Trace 查询暂不可用", "items", List.of());
        }
    }

    Map<String, Object> trace(String traceId, String environment, String code) {
        return trace(traceId, environment, code, deadline());
    }

    Map<String, Object> trace(String traceId, String environment, String code, long deadline) {
        environment(environment);
        traceId = traceIdentifier(traceId);
        if (traceId.isBlank()) throw invalid("Trace ID 无效");
        if (!enabled) throw new BusinessException(ErrorCode.NOT_FOUND, "Trace 未启用");
        JsonNode root = read(tempo + "/api/traces/" + traceId, deadline);
        List<Map<String, Object>> spans = project(root, environment);
        if (spans.isEmpty()
                || code != null
                        && !code.isBlank()
                        && spans.stream()
                                .noneMatch(span -> runtimeCode(code).equals(span.get("ciCode")))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "当前服务与环境内未找到此Trace");
        }
        return Map.of(
                "traceId",
                traceId,
                "environment",
                environment,
                "spans",
                spans,
                "partial",
                !environment.equals("ALL"),
                "source",
                "TEMPO",
                "message",
                "只返回授权环境的脱敏操作摘要；未返回HTTP头、正文、SQL、Prompt或异常正文");
    }

    static boolean hasDirectedRelation(
            List<Map<String, Object>> spans, String source, String target) {
        String from = runtimeCode(source);
        String to = runtimeCode(target);
        // Same-CI producer/consumer spans are real; two logical aliases still cannot invent
        // identity.
        boolean sameRuntime = from.equals(to);
        if (sameRuntime && !source.equals(target)) return false;
        for (var parent : spans) {
            if (!from.equals(parent.get("ciCode"))
                    || !Set.of("CLIENT", "PRODUCER").contains(parent.get("kind"))
                    || sameRuntime && !"PRODUCER".equals(parent.get("kind"))) continue;
            String peer = "opsagent:" + parent.get("environment") + ":" + to;
            if (!sameRuntime && peer.equals(parent.get("peerService"))) return true;
            String parentId = String.valueOf(parent.getOrDefault("spanId", ""));
            if (parentId.isBlank()) continue;
            for (var child : spans) {
                if (to.equals(child.get("ciCode"))
                        && java.util.Objects.equals(
                                parent.get("environment"), child.get("environment"))
                        && parentId.equals(child.get("parentSpanId"))
                        && ("CLIENT".equals(parent.get("kind"))
                                        && "SERVER".equals(child.get("kind"))
                                || "PRODUCER".equals(parent.get("kind"))
                                        && "CONSUMER".equals(child.get("kind")))) return true;
            }
        }
        return false;
    }

    static String traceIdentifier(String value) {
        return value != null && value.matches("[a-fA-F0-9]{1,32}") && !value.matches("0+")
                ? "0".repeat(32 - value.length()) + value.toLowerCase(Locale.ROOT)
                : "";
    }

    static String observedRelation(JsonNode metric) {
        String connection = metric.path("connection_type").asText();
        if ("database".equals(connection)) return "ACCESSES_DATA";
        return "messaging_system".equals(connection)
                        || !metric.path("client_messaging_system").asText().isBlank()
                        || !metric.path("server_messaging_system").asText().isBlank()
                ? "MESSAGES"
                : "CALLS";
    }

    static List<Map<String, Object>> project(JsonNode root, String environment) {
        List<Map<String, Object>> result = new ArrayList<>();
        JsonNode batches = root.has("batches") ? root.path("batches") : root.path("resourceSpans");
        for (JsonNode batch : batches) {
            Map<String, String> resource = attributes(batch.path("resource").path("attributes"));
            String env = resource.getOrDefault("opsagent.environment", "");
            String ci = resource.getOrDefault("opsagent.ci.code", "");
            if (!resource.getOrDefault("service.namespace", "").equals("opsagent")
                    || !Set.of("PROD", "DEMO", "STAGING", "TEST", "DEV").contains(env)
                    || !ci.matches("[a-zA-Z0-9_-]{1,64}")
                    || !environment.equals("ALL") && !environment.equals(env)) continue;
            JsonNode scopes =
                    batch.has("scopeSpans")
                            ? batch.path("scopeSpans")
                            : batch.path("instrumentationLibrarySpans");
            for (JsonNode scope : scopes)
                for (JsonNode span : scope.path("spans")) {
                    if (result.size() >= 500) return result;
                    Map<String, String> attrs = attributes(span.path("attributes"));
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("spanId", identifier(span.path("spanId").asText()));
                    value.put("parentSpanId", identifier(span.path("parentSpanId").asText()));
                    value.put("ciCode", ci);
                    value.put("environment", env);
                    value.put(
                            "instanceId",
                            safeIdentifier(resource.getOrDefault("service.instance.id", ""), 150));
                    value.put("kind", kind(span.path("kind")));
                    Instant start = nanoTime(span.path("startTimeUnixNano"));
                    Instant end = nanoTime(span.path("endTimeUnixNano"));
                    value.put("startTime", start.toString());
                    value.put(
                            "durationMs",
                            Math.max(0, Duration.between(start, end).toNanos() / 1_000_000.0));
                    String status = span.path("status").path("code").asText("UNSET");
                    value.put(
                            "status",
                            Set.of(
                                                    "0",
                                                    "1",
                                                    "2",
                                                    "UNSET",
                                                    "OK",
                                                    "ERROR",
                                                    "STATUS_CODE_UNSET",
                                                    "STATUS_CODE_OK",
                                                    "STATUS_CODE_ERROR")
                                            .contains(status)
                                    ? status
                                    : "UNSET");
                    value.put("operation", operation(attrs));
                    value.put("peerService", safeService(attrs.getOrDefault("peer.service", "")));
                    value.put("links", streamLinks(span.path("links")));
                    value.put(
                            "metadata",
                            Map.of(
                                    "runtimeKind",
                                    runtimeKind(
                                            resource.getOrDefault("opsagent.runtime.kind", "JVM")),
                                    "hostName",
                                    safeIdentifier(resource.getOrDefault("host.name", ""), 100),
                                    "processId",
                                    resource.getOrDefault("process.pid", "").matches("[0-9]{1,12}")
                                            ? resource.get("process.pid")
                                            : "",
                                    "runtimeName",
                                    safeRuntimeText(
                                            resource.getOrDefault("process.runtime.name", "JVM"),
                                            80),
                                    "runtimeVersion",
                                    safeRuntimeText(
                                            resource.getOrDefault("process.runtime.version", ""),
                                            80)));
                    result.add(value);
                }
        }
        return result;
    }

    private static String operation(Map<String, String> attributes) {
        String route = attributes.getOrDefault("http.route", "");
        if (route.matches("[/a-zA-Z0-9_{}.*:-]{1,160}")) return route;
        String database =
                attributes
                        .getOrDefault(
                                "db.operation.name", attributes.getOrDefault("db.operation", ""))
                        .toUpperCase(Locale.ROOT);
        if (Set.of(
                        "SELECT", "INSERT", "UPDATE", "DELETE", "CALL", "GET", "SET", "DEL", "MGET",
                        "MSET", "PING", "HGET", "HSET", "EXISTS", "EXPIRE", "EVAL", "EXEC", "MULTI",
                        "SCAN", "SEARCH", "INDEX", "BULK")
                .contains(database)) return database;
        String messaging =
                attributes
                        .getOrDefault(
                                "messaging.operation.type",
                                attributes.getOrDefault("messaging.operation", ""))
                        .toLowerCase(Locale.ROOT);
        return Set.of("publish", "send", "receive", "process", "settle", "create")
                        .contains(messaging)
                ? messaging
                : "操作";
    }

    private static String runtimeKind(String value) {
        return Set.of("JVM", "DOCKER", "DOCKER_COMPOSE", "KUBERNETES", "PROCESS", "HOST")
                        .contains(value)
                ? value
                : "JVM";
    }

    private static String safeIdentifier(String value, int max) {
        return value.length() <= max && value.matches("[a-zA-Z0-9_.:@/-]*") ? value : "";
    }

    private static String safeRuntimeText(String value, int max) {
        return value.length() <= max && value.matches("[a-zA-Z0-9_.+() -]*") ? value : "";
    }

    private static List<Map<String, String>> streamLinks(JsonNode links) {
        List<Map<String, String>> values = new ArrayList<>();
        for (JsonNode link : links) {
            if (values.size() == 10) break;
            values.add(
                    Map.of(
                            "traceId",
                            identifier(link.path("traceId").asText()),
                            "spanId",
                            identifier(link.path("spanId").asText())));
        }
        return values;
    }

    private static Map<String, String> attributes(JsonNode values) {
        Map<String, String> result = new HashMap<>();
        for (JsonNode field : values) {
            JsonNode value = field.path("value");
            result.put(
                    field.path("key").asText(),
                    value.has("stringValue")
                            ? value.path("stringValue").asText()
                            : value.path("intValue").asText());
        }
        return result;
    }

    private JsonNode query(String promql) {
        JsonNode result = read(prometheus + "/api/v1/query?query=" + encode(promql));
        if (!result.path("status").asText().equals("success"))
            throw new IllegalStateException("Prometheus unavailable");
        return result.path("data").path("result");
    }

    private JsonNode read(String url) {
        return read(url, deadline());
    }

    private JsonNode read(String url, long deadline) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            long remaining = Math.min(TimeUnit.SECONDS.toNanos(5), deadline - System.nanoTime());
            if (remaining <= 0) throw new IllegalStateException("Evidence query deadline");
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofNanos(remaining))
                            .header("Accept", "application/json")
                            .GET()
                            .build();
            pending = http.sendAsync(request, ignored -> new LimitedBody());
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (response.statusCode() / 100 != 2)
                throw new IllegalStateException("Source response unavailable");
            return json.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Source interrupted");
        } catch (Exception failure) {
            throw new IllegalStateException("Trace source unavailable");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    /**
     * Bounds response bytes while the request deadline also covers slow response bodies.
     *
     * @author heyu
     */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long received;
        private boolean done;

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
            if (done) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > 4_000_000) {
                done = true;
                subscription.cancel();
                delegate.onError(new IllegalStateException("Evidence size limit"));
            } else delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable failure) {
            if (!done) {
                done = true;
                delegate.onError(failure);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                delegate.onComplete();
            }
        }
    }

    private static String base(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || !Set.of("http", "https").contains(uri.getScheme()))
            throw new IllegalArgumentException("Invalid fixed observability source");
        return value.replaceAll("/+$", "");
    }

    private static Double number(JsonNode row) {
        JsonNode value = row.path("value");
        if (value.size() < 2) return null;
        double number = value.get(1).asDouble(Double.NaN);
        return Double.isFinite(number) ? number : null;
    }

    private static String pairKey(JsonNode labels) {
        return labels.path("client").asText()
                + "|"
                + labels.path("server").asText()
                + "|"
                + labels.path("connection_type").asText()
                + "|"
                + labels.path("virtual_node").asText()
                + "|"
                + labels.path("client_messaging_system").asText()
                + "|"
                + labels.path("server_messaging_system").asText();
    }

    static String id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String safeService(String value) {
        return value.matches("[a-zA-Z0-9_.:-]{0,160}") ? value : "未解析服务";
    }

    static Instant nanoTime(JsonNode value) {
        try {
            long nanos = Long.parseLong(value.asText("0"));
            return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
        } catch (RuntimeException invalid) {
            return Instant.EPOCH;
        }
    }

    private static String identifier(String value) {
        if (value.matches("[a-f0-9]{16}|[a-f0-9]{32}|")) return value;
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length == 8 || decoded.length == 16
                    ? java.util.HexFormat.of().formatHex(decoded)
                    : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String kind(JsonNode kind) {
        if (kind.isTextual()) {
            String value = kind.asText().replace("SPAN_KIND_", "");
            return Set.of("INTERNAL", "SERVER", "CLIENT", "PRODUCER", "CONSUMER", "UNSPECIFIED")
                            .contains(value)
                    ? value
                    : "UNSPECIFIED";
        }
        return switch (kind.asInt()) {
            case 1 -> "INTERNAL";
            case 2 -> "SERVER";
            case 3 -> "CLIENT";
            case 4 -> "PRODUCER";
            case 5 -> "CONSUMER";
            default -> "UNSPECIFIED";
        };
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    /**
     * @author heyu
     */
    record Graph(
            String status, String message, Instant fetchedAt, List<Map<String, Object>> edges) {}
}
