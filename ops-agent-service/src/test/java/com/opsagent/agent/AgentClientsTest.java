package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens.Context;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.web.GlobalExceptionHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * 真实内部 HTTP 响应保留授权错误，不将工作区权限拒绝转换为依赖不可用。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentClientsTest {
    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
        "200,40100,UNAUTHENTICATED",
        "200,40300,FORBIDDEN",
        "200,40400,NOT_FOUND",
        "200,40000,MIDDLEWARE_UNAVAILABLE",
        "200,50000,MIDDLEWARE_UNAVAILABLE",
        "200,99999,MIDDLEWARE_UNAVAILABLE",
        "401,50000,UNAUTHENTICATED",
        "403,50000,FORBIDDEN",
        "404,50000,NOT_FOUND",
        "503,0,MIDDLEWARE_UNAVAILABLE"
    })
    void mapsStandardEnvelopeAndHttpFailuresWithoutImplicitRetry(
            int statusCode, int envelopeCode, ErrorCode expected) throws Exception {
        try (ReplyServer server = new ReplyServer(new Reply(statusCode, failure(envelopeCode)))) {
            BusinessException failure =
                    assertThrows(
                            BusinessException.class,
                            () ->
                                    server.clients()
                                            .call(
                                                    "ticket",
                                                    "/internal/agent/tickets/7/workspace-context",
                                                    "GET",
                                                    null,
                                                    actor()));
            assertEquals(expected, failure.getErrorCode());
            assertEquals("access denied", failure.getMessage());
            assertEquals(
                    List.of("GET /internal/agent/tickets/7/workspace-context HTTP/1.1"),
                    server.requests());
        }
    }

    @Test
    void successfulEnvelopeStillReturnsOnlyData() throws Exception {
        ObjectNode response = AgentJson.object().put("code", 0);
        response.set("data", AgentJson.object().put("id", 7));
        try (ReplyServer server = new ReplyServer(new Reply(200, response.toString()))) {
            assertEquals(
                    AgentJson.object().put("id", 7),
                    server.clients()
                            .call("ticket", "/internal/agent/tickets/7", "GET", null, actor()));
            assertEquals(List.of("GET /internal/agent/tickets/7 HTTP/1.1"), server.requests());
        }
    }

    @ParameterizedTest
    @CsvSource({"40100", "40300", "40400"})
    void deniedWorkspacePreservesEnvelopeAndNeverReadsRunsOrApprovals(int envelopeCode)
            throws Exception {
        ObjectNode identity = AgentJson.object().put("code", 0);
        ObjectNode data =
                AgentJson.object()
                        .put("active", true)
                        .put("userId", -1)
                        .put("username", "visitor")
                        .put("expiresAt", Instant.now().plusSeconds(600).toString());
        data.putArray("roles").add("DEMO");
        identity.set("data", data);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(-1, "visitor", "test-session", List.of("DEMO")),
                                null,
                                List.of()));
        try (ReplyServer server =
                new ReplyServer(
                        new Reply(200, identity.toString()),
                        new Reply(200, failure(envelopeCode)))) {
            AgentStore store = mock(AgentStore.class);
            AgentService service = mock(AgentService.class);
            var mvc =
                    MockMvcBuilders.standaloneSetup(
                                    new AgentController(
                                            service,
                                            store,
                                            new AgentWorkspace(store, server.clients())))
                            .setControllerAdvice(new GlobalExceptionHandler())
                            .build();

            mvc.perform(get("/api/automation/tickets/7/workspace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(envelopeCode))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verifyNoInteractions(store, service);
            assertEquals(
                    List.of(
                            "GET /internal/agent/actors/-1 HTTP/1.1",
                            "GET /internal/agent/tickets/7/workspace-context HTTP/1.1"),
                    server.requests());
        }
    }

    private static String failure(int code) {
        ObjectNode response = AgentJson.object().put("code", code).put("message", "access denied");
        // Even an erroneous failure envelope containing data must never return it to the caller.
        response.set("data", AgentJson.object().put("privateRun", "another-owner"));
        return response.toString();
    }

    private static Context actor() {
        return new Context(
                -1,
                "visitor",
                List.of("DEMO"),
                "test-run",
                AgentTargets.ORDER,
                Instant.now().plusSeconds(600));
    }

    /**
     * @author heyu
     */
    private record Reply(int status, String body) {}

    /**
     * 有界本地 HTTP 服务，不引入额外依赖或访问线上服务。
     *
     * @author heyu
     */
    private static final class ReplyServer implements AutoCloseable {
        private final ServerSocket server;
        private final FutureTask<List<String>> exchange;

        ReplyServer(Reply... replies) throws Exception {
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(5000);
            exchange =
                    new FutureTask<>(
                            () -> {
                                List<String> requests = new ArrayList<>();
                                for (Reply reply : replies) {
                                    try (Socket socket = server.accept()) {
                                        socket.setSoTimeout(5000);
                                        BufferedReader reader =
                                                new BufferedReader(
                                                        new InputStreamReader(
                                                                socket.getInputStream(),
                                                                StandardCharsets.UTF_8));
                                        requests.add(reader.readLine());
                                        String line = reader.readLine();
                                        while (line != null && !line.isEmpty())
                                            line = reader.readLine();
                                        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                                        String headers =
                                                "HTTP/1.1 "
                                                        + reply.status()
                                                        + " Test\r\n"
                                                        + "Content-Type: application/json\r\n"
                                                        + "Content-Length: "
                                                        + body.length
                                                        + "\r\n"
                                                        + "Connection: close\r\n\r\n";
                                        socket.getOutputStream()
                                                .write(headers.getBytes(StandardCharsets.UTF_8));
                                        socket.getOutputStream().write(body);
                                        socket.getOutputStream().flush();
                                    }
                                }
                                return requests;
                            });
            Thread thread = new Thread(exchange, "agent-clients-local-http-test");
            thread.setDaemon(true);
            thread.start();
        }

        AgentClients clients() {
            String base = "http://127.0.0.1:" + server.getLocalPort();
            return new AgentClients("test-internal-secret-".repeat(3), base, base, base, base);
        }

        List<String> requests() throws Exception {
            return exchange.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }
}
