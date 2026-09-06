package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Exercises the real HTTP parser so query evaluation time cannot renew an old scrape.
 *
 * @author heyu
 * @since 2026/9/3
 */
class PrometheusAdapterTimestampTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant evaluatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private FutureTask<Void> exchanges;
    private PrometheusAdapter adapter;
    private volatile Function<String, Map<String, Object>> response;

    @BeforeEach
    void startFixture() throws Exception {
        response = query -> vector(evaluatedAt, "0");
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(5000);
        exchanges =
                new FutureTask<>(
                        () -> {
                            for (int count = 0; count < 16 && !server.isClosed(); count++) {
                                try (Socket socket = server.accept()) {
                                    socket.setSoTimeout(5000);
                                    BufferedReader reader =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            socket.getInputStream(),
                                                            StandardCharsets.UTF_8));
                                    String request = reader.readLine();
                                    String line = reader.readLine();
                                    while (line != null && !line.isEmpty())
                                        line = reader.readLine();
                                    URI uri = URI.create(request.split(" ")[1]);
                                    boolean targets = uri.getPath().endsWith("/targets");
                                    String encoded =
                                            targets
                                                    ? ""
                                                    : uri.getRawQuery()
                                                            .substring("query=".length());
                                    String query =
                                            URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                                    queries.add(query);
                                    byte[] body =
                                            json.writeValueAsBytes(
                                                    targets
                                                            ? Map.of(
                                                                    "status",
                                                                    "success",
                                                                    "data",
                                                                    Map.of(
                                                                            "activeTargets",
                                                                            List.of()))
                                                            : response.apply(query));
                                    String headers =
                                            "HTTP/1.1 200 OK\r\n"
                                                    + "Content-Type: application/json\r\n"
                                                    + "Content-Length: "
                                                    + body.length
                                                    + "\r\nConnection: close\r\n\r\n";
                                    socket.getOutputStream()
                                            .write(headers.getBytes(StandardCharsets.UTF_8));
                                    socket.getOutputStream().write(body);
                                    socket.getOutputStream().flush();
                                } catch (SocketException exception) {
                                    if (!server.isClosed()) throw exception;
                                }
                            }
                            return null;
                        });
        Thread thread = new Thread(exchanges, "prometheus-timestamp-local-http-test");
        thread.setDaemon(true);
        thread.start();
        adapter = new PrometheusAdapter(json);
        ReflectionTestUtils.setField(
                adapter, "baseUrl", "http://127.0.0.1:" + server.getLocalPort());
    }

    @AfterEach
    void stopFixture() throws Exception {
        if (server != null) server.close();
        if (exchanges != null) exchanges.get(2, TimeUnit.SECONDS);
    }

    @Test
    void rawScrapeTimeExpiresEvenWhenTheInstantQueryEvaluationTimeIsRecent() throws Exception {
        Instant scrapedAt = evaluatedAt.minusSeconds(85);
        response =
                query ->
                        query.startsWith("up[")
                                ? matrix(List.of(series("one", List.of(point(scrapedAt, "1")))))
                                : vector(evaluatedAt, "1");

        // Prometheus instant-vector values carry evaluation time, not the raw selector timestamp.
        var evaluated = adapter.query("up");
        assertThat(evaluated)
                .singleElement()
                .satisfies(sample -> assertThat(sample.observedAt()).isEqualTo(evaluatedAt));

        var snapshot = adapter.collect("15m");
        var raw = snapshot.series().get("up");
        assertThat(queries).contains("up[90s]");
        assertThat(queries)
                .anyMatch(
                        query ->
                                query.startsWith(
                                        "sum by(job,ci_code,environment,namespace,cluster)"
                                                + "(rate(http_server_requests_seconds_count["));
        assertThat(queries)
                .anyMatch(
                        query ->
                                query.contains(
                                        "histogram_quantile(0.95, sum"
                                            + " by(job,ci_code,environment,namespace,cluster,le)"));
        assertThat(raw)
                .singleElement()
                .satisfies(sample -> assertThat(sample.observedAt()).isEqualTo(scrapedAt));

        // A valid 85-second-old range sample expires during ten seconds of transport/cache delay.
        Instant consumedAt = evaluatedAt.plusSeconds(10);
        var health = new NodeHealthService();
        assertThat(
                        health.compute(
                                        "j",
                                        "ACTIVE",
                                        raw,
                                        List.of(),
                                        Map.of(),
                                        consumedAt,
                                        null,
                                        null)
                                .health())
                .isEqualTo("UNKNOWN");
        assertThat(
                        health.compute(
                                        "j",
                                        "ACTIVE",
                                        evaluated,
                                        List.of(),
                                        Map.of("cpuUsage", 3),
                                        consumedAt,
                                        null,
                                        null)
                                .health())
                .isEqualTo("HEALTHY");
    }

    @Test
    void usesTheLatestRawPointPerInstanceIncludingARealZero() {
        Instant prior = evaluatedAt.minusSeconds(30);
        Instant recent = evaluatedAt.minusSeconds(5);
        response =
                query ->
                        query.startsWith("up[")
                                ? matrix(
                                        List.of(
                                                series(
                                                        "one",
                                                        List.of(
                                                                point(prior, "0"),
                                                                point(recent, "1"))),
                                                series(
                                                        "two",
                                                        List.of(
                                                                point(prior, "1"),
                                                                point(recent, "0")))))
                                : vector(evaluatedAt, "0");

        var snapshot = adapter.collect("15m");
        var raw = snapshot.series().get("up");
        assertThat(raw).hasSize(2);
        assertThat(raw).extracting(PrometheusAdapter.Sample::value).containsExactly(1.0, 0.0);
        assertThat(raw).extracting(PrometheusAdapter.Sample::observedAt).containsOnly(recent);
        var state =
                new NodeHealthService()
                        .compute("j", "ACTIVE", raw, List.of(), Map.of(), evaluatedAt, null, null);
        assertThat(state.health()).isEqualTo("UNKNOWN");
        assertThat(state.healthyInstances()).isEqualTo(1);
        assertThat(state.totalInstances()).isEqualTo(2);
        assertThat(snapshot.series().get("rps"))
                .singleElement()
                .satisfies(
                        sample -> {
                            assertThat(sample.value()).isZero();
                            assertThat(sample.observedAt()).isEqualTo(evaluatedAt);
                        });
    }

    @Test
    void honorsConfiguredWindowAndDoesNotResurrectEarlierValuesAfterInvalidOrMissingSamples() {
        ReflectionTestUtils.setField(adapter, "maximumSampleAge", 45L);
        response =
                query ->
                        query.startsWith("up[")
                                ? matrix(
                                        List.of(
                                                series(
                                                        "invalid-last",
                                                        List.of(
                                                                point(
                                                                        evaluatedAt.minusSeconds(
                                                                                20),
                                                                        "1"),
                                                                point(
                                                                        evaluatedAt.minusSeconds(5),
                                                                        "NaN"))),
                                                series("empty", List.of())))
                                : vector(evaluatedAt, "0");

        var snapshot = adapter.collect("5m");
        assertThat(queries.get(0)).isEqualTo("up[45s]");
        assertThat(snapshot.healthy()).isTrue();
        assertThat(snapshot.series().get("up")).isEmpty();
        var state =
                new NodeHealthService()
                        .compute(
                                "j",
                                "ACTIVE",
                                snapshot.series().get("up"),
                                List.of(),
                                Map.of(),
                                evaluatedAt,
                                null,
                                null);
        assertThat(state.health()).isEqualTo("UNKNOWN");
        assertThat(state.totalInstances()).isNull();
    }

    private Map<String, Object> vector(Instant time, String value) {
        return Map.of(
                "status",
                "success",
                "data",
                Map.of(
                        "resultType",
                        "vector",
                        "result",
                        List.of(
                                Map.of(
                                        "metric",
                                        Map.of("job", "j"),
                                        "value",
                                        point(time, value)))));
    }

    private Map<String, Object> matrix(List<Map<String, Object>> series) {
        return Map.of(
                "status", "success", "data", Map.of("resultType", "matrix", "result", series));
    }

    private Map<String, Object> series(String instance, List<List<Object>> values) {
        return Map.of("metric", Map.of("job", "j", "instance", instance), "values", values);
    }

    private List<Object> point(Instant time, String value) {
        return List.of(time.toEpochMilli() / 1000.0, value);
    }
}
