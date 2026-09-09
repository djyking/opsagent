package com.opsagent.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An ended experience immediately rejects a still-valid JWT at every public business entry point.
 *
 * @author heyu
 */
class VisitorLeaseFilterTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameUnexpiredJwtStopsRagWritesTicketConfirmationAndConfigurationReadsAfterRevocation()
            throws Exception {
        var jwt = jwt();
        String token = jwt.issue(-23, "visitor", List.of("DEMO"), Duration.ofMinutes(30)).token();
        try (var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            FutureTask<Void> replies =
                    new FutureTask<>(
                            () -> {
                                for (boolean active : List.of(true, false, false, false)) {
                                    try (var socket = server.accept()) {
                                        var reader =
                                                new BufferedReader(
                                                        new InputStreamReader(
                                                                socket.getInputStream(),
                                                                StandardCharsets.UTF_8));
                                        assertThat(reader.readLine())
                                                .startsWith("GET /internal/agent/actors/-23 ");
                                        String line = reader.readLine();
                                        while (line != null && !line.isEmpty()) {
                                            line = reader.readLine();
                                        }
                                        String body =
                                                "{\"code\":0,\"data\":{\"userId\":-23,\"username\":\"visitor\","
                                                        + "\"roles\":[\"DEMO\"],\"active\":"
                                                        + active
                                                        + ",\"expiresAt\":\""
                                                        + Instant.now().plusSeconds(86400)
                                                        + "\",\"reasonCode\":\""
                                                        + (active ? "" : "VISITOR_REVOKED")
                                                        + "\"}}";
                                        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                                        String headers =
                                                "HTTP/1.1 200 OK\r\n"
                                                    + "Content-Type: application/json\r\n"
                                                    + "Connection: close\r\n"
                                                    + "Content-Length: "
                                                        + payload.length
                                                        + "\r\n\r\n";
                                        socket.getOutputStream()
                                                .write(headers.getBytes(StandardCharsets.UTF_8));
                                        socket.getOutputStream().write(payload);
                                        socket.getOutputStream().flush();
                                    }
                                }
                                return null;
                            });
            Thread worker = new Thread(replies, "visitor-lease-test");
            worker.setDaemon(true);
            worker.start();
            var verifier =
                    VisitorSessionVerifier.remote(
                            "test-internal-secret-at-least-thirty-two-bytes",
                            "http://localhost:" + server.getLocalPort());
            var filter = new JwtAuthenticationFilter(jwt, verifier);
            assertThat(invoke(filter, token, "POST", "/api/rag/conversations").getStatus())
                    .isEqualTo(204);
            for (String path :
                    List.of(
                            "/api/rag/conversations",
                            "/api/tickets/12/event-lifecycle",
                            "/api/platform/configuration/files")) {
                var response =
                        invoke(
                                filter,
                                token,
                                path.contains("configuration") ? "GET" : "POST",
                                path);
                assertThat(response.getStatus()).isEqualTo(403);
                assertThat(response.getContentAsString()).contains("VISITOR_REVOKED");
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            }
            replies.get(5, TimeUnit.SECONDS);
            assertThat(jwt.parse(token).userId()).isEqualTo(-23);
        }
    }

    @Test
    void authLocalLeaseEndpointsAndInternalActorCallsNeverLoopBackThroughRemoteVerifier()
            throws Exception {
        var jwt = jwt();
        String token = jwt.issue(-23, "visitor", List.of("DEMO")).token();
        AtomicBoolean verified = new AtomicBoolean();
        var filter = new JwtAuthenticationFilter(jwt, principal -> verified.set(true));
        assertThat(invoke(filter, token, "GET", "/api/auth/me").getStatus()).isEqualTo(204);
        assertThat(invoke(filter, token, "POST", "/api/auth/end-experience").getStatus())
                .isEqualTo(204);
        assertThat(invoke(filter, token, "GET", "/internal/agent/actors/-23").getStatus())
                .isEqualTo(204);
        assertThat(verified).isFalse();
    }

    @Test
    void unavailableIdentitySourceFailsClosedBeforeAnyBusinessController() throws Exception {
        var jwt = jwt();
        String token = jwt.issue(-23, "visitor", List.of("DEMO")).token();
        var filter =
                new JwtAuthenticationFilter(
                        jwt,
                        principal -> {
                            throw new com.opsagent.common.core.BusinessException(
                                    com.opsagent.common.core.ErrorCode.MIDDLEWARE_UNAVAILABLE,
                                    "ACTOR_REVALIDATION_UNAVAILABLE");
                        });
        assertThat(invoke(filter, token, "POST", "/api/rag/conversations").getStatus())
                .isEqualTo(503);
    }

    private JwtService jwt() {
        var properties = new JwtProperties();
        properties.setSecret("visitor-filter-test-signature-at-least-thirty-two-bytes");
        return new JwtService(properties);
    }

    private MockHttpServletResponse invoke(
            JwtAuthenticationFilter filter, String token, String method, String path)
            throws Exception {
        SecurityContextHolder.clearContext();
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        return response;
    }
}
