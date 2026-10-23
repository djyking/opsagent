package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exercises real headers, body stalls, cancellation and envelope validation over loopback HTTP.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Timeout(15)
class HarnessHttpTransportTest {
    private final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void bodyDeadlineDoesNotWaitForTheServerToCompleteOrReplayThePost() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{}"));
            server.enqueue(
                    new MockResponse()
                            .setBody("{\"accepted\":true}")
                            .setBodyDelay(2, TimeUnit.SECONDS));
            server.start();
            var warmup =
                    HttpRequest.newBuilder(server.url("/warmup").uri())
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();
            assertThat(HarnessHttpTransport.send(http, warmup, 1024).statusCode()).isEqualTo(200);
            var request =
                    HttpRequest.newBuilder(server.url("/command").uri())
                            .timeout(Duration.ofMillis(400))
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build();
            long began = System.nanoTime();
            assertThatThrownBy(() -> HarnessHttpTransport.send(http, request, 1024))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - began))
                    .isLessThan(Duration.ofMillis(1800));
            assertThat(server.getRequestCount()).isEqualTo(2);
            server.enqueue(new MockResponse().setBody("{}"));
            var next =
                    HttpRequest.newBuilder(server.url("/next").uri())
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build();
            assertThat(HarnessHttpTransport.send(http, next, 1024).body())
                    .isEqualTo("{}".getBytes());
            assertThat(server.getRequestCount()).isEqualTo(3);
        }
    }

    @Test
    void headerDeadlineAlsoRemainsBounded() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("{}"));
            server.start();
            var request =
                    HttpRequest.newBuilder(server.url("/slow-headers").uri())
                            .timeout(Duration.ofMillis(400))
                            .GET()
                            .build();
            assertThatThrownBy(() -> HarnessHttpTransport.send(http, request, 1024))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void byteLimitAppliesToChunkedBodiesAndExactLimitIsAccepted() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setChunkedBody("x".repeat(4097), 256));
            server.enqueue(new MockResponse().setChunkedBody("x".repeat(4096), 256));
            server.start();
            var request =
                    HttpRequest.newBuilder(server.url("/bounded").uri())
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build();
            assertThatThrownBy(() -> HarnessHttpTransport.send(http, request, 4096))
                    .isInstanceOf(HarnessHttpTransport.ResponseTooLargeException.class);
            assertThat(HarnessHttpTransport.send(http, request, 4096).body()).hasSize(4096);
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void interruptionStopsTheBodyWaitWithoutRetrying() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{}").throttleBody(1, 2, TimeUnit.SECONDS));
            server.start();
            var request =
                    HttpRequest.newBuilder(server.url("/interrupted").uri())
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
            var outcome = new AtomicReference<Throwable>();
            Thread caller =
                    new Thread(
                            () -> {
                                try {
                                    HarnessHttpTransport.send(http, request, 1024);
                                } catch (Throwable failure) {
                                    outcome.set(failure);
                                }
                            });
            caller.start();
            try {
                assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
                caller.interrupt();
                caller.join(1500);
                assertThat(caller.isAlive()).isFalse();
                assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
                assertThat(server.getRequestCount()).isEqualTo(1);
            } finally {
                caller.interrupt();
                caller.join(1500);
            }
        }
    }

    @Test
    void overflowingIdentityCodeCannotBecomeSuccessfulZero() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(
                    new MockResponse()
                            .setBody("{\"code\":4294967296,\"data\":{\"subject\":\"7\"}}"));
            server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"subject\":\"7\"}}"));
            server.start();
            var client =
                    new HarnessPlatformClient(
                            new ObjectMapper(),
                            server.url("/").toString(),
                            server.url("/").toString(),
                            "synthetic-application-credential-only-tests");
            assertThatThrownBy(() -> client.identity("synthetic-project", "synthetic-user-token"))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            failure ->
                                    assertThat(failure.getErrorCode())
                                            .isEqualTo(ErrorCode.FORBIDDEN));
            assertThat(
                            client.identity("synthetic-project", "synthetic-user-token")
                                    .path("subject")
                                    .asText())
                    .isEqualTo("7");
        }
    }

    @Test
    void timeoutClosesTheOriginalStalledConnection() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(6000);
            var sentHeaders = new CountDownLatch(1);
            var closed = new CountDownLatch(1);
            var serverFailure = new AtomicReference<Throwable>();
            Thread upstream =
                    new Thread(
                            () -> {
                                try (var socket = server.accept()) {
                                    socket.setSoTimeout(6000);
                                    var input = socket.getInputStream();
                                    var headers = new StringBuilder();
                                    while (!headers.toString().endsWith("\r\n\r\n")) {
                                        int value = input.read();
                                        if (value < 0)
                                            throw new IllegalStateException("Missing request");
                                        headers.append((char) value);
                                    }
                                    socket.getOutputStream()
                                            .write(
                                                    ("HTTP/1.1 200 OK\r\n"
                                                         + "Content-Length: 10\r\n\r\n"
                                                         + "{")
                                                            .getBytes(StandardCharsets.US_ASCII));
                                    socket.getOutputStream().flush();
                                    sentHeaders.countDown();
                                    try {
                                        if (input.read() == -1) closed.countDown();
                                    } catch (SocketException reset) {
                                        closed.countDown();
                                    }
                                } catch (Throwable failure) {
                                    serverFailure.set(failure);
                                }
                            });
            upstream.start();
            try {
                var request =
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + server.getLocalPort()
                                                        + "/stall"))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build();
                assertThatThrownBy(() -> HarnessHttpTransport.send(http, request, 1024))
                        .isInstanceOf(HttpTimeoutException.class);
                assertThat(sentHeaders.getCount()).isZero();
                assertThat(closed.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(serverFailure.get()).isNull();
            } finally {
                upstream.join(6500);
            }
        }
    }

    @Test
    void invalidMutationReplyPreservesUnknownOutcomeWithoutReplay() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("invalid JSON"));
            server.start();
            var client =
                    new HarnessPlatformClient(
                            new ObjectMapper(),
                            server.url("/").toString(),
                            server.url("/").toString(),
                            "synthetic-application-credential-only-tests");
            assertThatThrownBy(
                            () ->
                                    client.call(
                                            "/v1/projects/synthetic/runs",
                                            "POST",
                                            new ObjectMapper().createObjectNode(),
                                            "synthetic-user-token",
                                            "original-request-key",
                                            null))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            failure -> {
                                assertThat(failure.getErrorCode())
                                        .isEqualTo(ErrorCode.MIDDLEWARE_UNAVAILABLE);
                                assertThat(failure.getMessage())
                                        .isEqualTo("HARNESS_UPSTREAM_OUTCOME_UNKNOWN");
                            });
            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(server.takeRequest().getHeader("Idempotency-Key"))
                    .isEqualTo("original-request-key");
        }
    }
}
