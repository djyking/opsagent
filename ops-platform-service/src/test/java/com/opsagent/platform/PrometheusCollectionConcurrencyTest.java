package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies bounded parallel real HTTP collection, cancellation and honest partial results.
 *
 * @author heyu
 * @since 2026/9/3
 */
class PrometheusCollectionConcurrencyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant sampleAt = Instant.parse("2026-09-03T10:00:00Z");
    private final ExecutorService fixture = Executors.newCachedThreadPool();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch entered = new CountDownLatch(4);
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maximum = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private ServerSocket server;
    private PrometheusAdapter adapter;
    private volatile boolean holdAll;
    private volatile boolean holdUp;
    private volatile boolean failRps;

    @BeforeEach
    void start() throws Exception {
        server = new ServerSocket(0, 30, InetAddress.getByName("127.0.0.1"));
        fixture.submit(
                () -> {
                    while (!server.isClosed()) {
                        try {
                            Socket socket = server.accept();
                            fixture.submit(() -> respond(socket));
                        } catch (SocketException exception) {
                            if (!server.isClosed()) throw new IllegalStateException(exception);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
                });
        adapter = new PrometheusAdapter(json);
        ReflectionTestUtils.setField(
                adapter, "baseUrl", "http://127.0.0.1:" + server.getLocalPort());
    }

    private void respond(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            var input =
                    new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String request = input.readLine();
            if (request == null) return;
            String line;
            do {
                line = input.readLine();
            } while (line != null && !line.isEmpty());
            URI uri = URI.create(request.split(" ")[1]);
            boolean targets = uri.getPath().endsWith("/targets");
            String query =
                    targets
                            ? ""
                            : URLDecoder.decode(
                                    uri.getRawQuery().substring(6), StandardCharsets.UTF_8);
            requests.incrementAndGet();
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                if (holdAll || (holdUp && query.startsWith("up[")))
                    release.await(5, TimeUnit.SECONDS);
                boolean failed = failRps && query.startsWith("sum by(");
                byte[] body =
                        query.equals("oversized")
                                ? new byte[4 * 1024 * 1024 + 1]
                                : json.writeValueAsBytes(
                                        targets
                                                ? Map.of(
                                                        "status",
                                                        "success",
                                                        "data",
                                                        Map.of("activeTargets", List.of()))
                                                : Map.of(
                                                        "status",
                                                        "success",
                                                        "data",
                                                        Map.of(
                                                                "result",
                                                                List.of(
                                                                        Map.of(
                                                                                "metric",
                                                                                Map.of("job", "j"),
                                                                                "value",
                                                                                List.of(
                                                                                        sampleAt
                                                                                                .getEpochSecond(),
                                                                                        "1"))))));
                String headers =
                        "HTTP/1.1 "
                                + (failed ? "500 Error" : "200 OK")
                                + "\r\nContent-Type: application/json\r\nContent-Length: "
                                + body.length
                                + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            } finally {
                active.decrementAndGet();
            }
        } catch (Exception ignored) {
            // Deadline/oversize checks deliberately cancel HTTP before the fixture finishes
            // writing.
        }
    }

    @AfterEach
    void stop() throws Exception {
        release.countDown();
        adapter.close();
        server.close();
        fixture.shutdownNow();
        assertThat(fixture.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void runsFourRequestsTogetherButNeverExceedsFourAndPreservesSampleTime() throws Exception {
        holdAll = true;
        var result = fixture.submit(() -> adapter.collect("15m"));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(active.get()).isEqualTo(4);
        release.countDown();
        var snapshot = result.get(3, TimeUnit.SECONDS);
        assertThat(snapshot.healthy()).isTrue();
        assertThat(requests.get()).isEqualTo(10);
        assertThat(maximum.get()).isEqualTo(4);
        assertThat(snapshot.series()).hasSize(9);
        assertThat(snapshot.series().get("rps"))
                .singleElement()
                .satisfies(sample -> assertThat(sample.observedAt()).isEqualTo(sampleAt));
        assertThat(snapshot.checkedAt()).isAfter(sampleAt);
    }

    @Test
    void wholeBatchDeadlineReturnsWhileOneSourceIsStillBlockedAndKeepsOtherEvidence()
            throws Exception {
        holdUp = true;
        var result = fixture.submit(() -> adapter.collect("5m", Duration.ofMillis(700)));
        var snapshot = result.get(2, TimeUnit.SECONDS);
        assertThat(release.getCount()).isEqualTo(1);
        assertThat(snapshot.healthy()).isFalse();
        assertThat(snapshot.errors()).containsEntry("up", "QUERY_UNAVAILABLE");
        assertThat(snapshot.series().get("up")).isEmpty();
        assertThat(snapshot.series().get("rps"))
                .singleElement()
                .satisfies(sample -> assertThat(sample.observedAt()).isEqualTo(sampleAt));
        assertThat(snapshot.errors()).doesNotContainKey("targets");
    }

    @Test
    void failedQueryRemainsMissingWithoutDiscardingSuccessfulQueries() {
        failRps = true;
        var snapshot = adapter.collect("15m");
        assertThat(snapshot.healthy()).isFalse();
        assertThat(snapshot.errors()).containsOnlyKeys("rps");
        assertThat(snapshot.series().get("rps")).isEmpty();
        assertThat(snapshot.series().get("cpuUsage")).hasSize(1);
        assertThat(snapshot.series().get("up")).hasSize(1);
    }

    @Test
    void directTrafficQueryStillEnforcesTheExistingResponseBodyLimit() {
        assertThatThrownBy(() -> adapter.query("oversized"))
                .hasRootCauseMessage("Prometheus response too large");
    }
}
