package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实HTTP测试固定工单入口与签名目标约束，拒绝目标不符及下游权限失败。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DiagnosticTicketClientTest {
    private static final String SECRET = "fixture-only-diagnostic-internal-signing-key-0123456789";

    @Test
    void forwardsActorWithTicketAudienceAndEnforcesReturnedTarget() throws Exception {
        ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(6000);
        AtomicReference<String> affected = new AtomicReference<>("svc");
        FutureTask<Void> fixture =
                new FutureTask<>(
                        () -> {
                            for (int count = 0; count < 2; count++) {
                                try (var socket = server.accept()) {
                                    socket.setSoTimeout(6000);
                                    var reader =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            socket.getInputStream(),
                                                            StandardCharsets.UTF_8));
                                    assertThat(reader.readLine())
                                            .contains("GET /internal/agent/tickets/71");
                                    String authorization = null;
                                    String line;
                                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                                        if (line.startsWith("Authorization: "))
                                            authorization = line.substring(15);
                                    }
                                    var actor =
                                            new InternalActorTokens(SECRET)
                                                    .verify(authorization, "ticket");
                                    assertThat(actor.userId()).isEqualTo(7);
                                    assertThat(actor.targetCode()).isEqualTo("svc");
                                    byte[] body =
                                            ("{\"code\":0,\"data\":{\"id\":71,\"affectedCiCode\":\""
                                                            + affected.get()
                                                            + "\"}}")
                                                    .getBytes(StandardCharsets.UTF_8);
                                    String headers =
                                            "HTTP/1.1 200 OK\r\nContent-Length: "
                                                    + body.length
                                                    + "\r\nConnection: close\r\n\r\n";
                                    socket.getOutputStream()
                                            .write(headers.getBytes(StandardCharsets.UTF_8));
                                    socket.getOutputStream().write(body);
                                    socket.getOutputStream().flush();
                                }
                            }
                            return null;
                        });
        Thread thread = new Thread(fixture, "diagnostic-ticket-local-http-test");
        thread.setDaemon(true);
        thread.start();
        try {
            var client =
                    new DiagnosticTicketClient(
                            new ObjectMapper(),
                            SECRET,
                            "http://127.0.0.1:" + server.getLocalPort());
            var actor =
                    new InternalActorTokens.Context(
                            7,
                            "operator",
                            List.of("OPS"),
                            "run",
                            "svc",
                            Instant.now().plusSeconds(60));
            assertThat(client.authorized(71, actor).path("id").asLong()).isEqualTo(71);
            affected.set("other-service");
            assertThatThrownBy(() -> client.authorized(71, actor))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("身份不匹配");
        } finally {
            server.close();
            fixture.get(2, TimeUnit.SECONDS);
        }
    }
}
