package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.InternalActorTokens;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolver transport preserves explicit empty labels and cannot turn an outage into a guessed CI.
 *
 * @author heyu
 * @since 2026/9/3
 */
class AlertTargetClientTest {
    private static final String SECRET = "alert-target-client-test-secret-not-real-credentials";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsOnlyBoundIdentityLabelsAndDedicatedServiceScope() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    """
{"code":0,"data":{"status":"TARGET_INVALID","targetCode":"","environment":"",
"message":"规范 CI 为空"}}
"""));
            var client = new AlertTargetClient(SECRET, server.url("/").toString());
            var labels =
                    json.createObjectNode()
                            .put("ci_code", "")
                            .put("service", "opsagent-agent")
                            .put("environment", "PROD")
                            .put("unrelated_private_label", "not-forwarded");
            var result = client.resolve(labels, "a".repeat(64));
            assertThat(result.matched()).isFalse();
            assertThat(result.message()).isEqualTo("规范 CI 为空");
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/internal/platform/alert-targets/resolve");
            var body = json.readTree(request.getBody().readUtf8());
            assertThat(body.size()).isEqualTo(3);
            assertThat(body.has("ci_code")).isTrue();
            assertThat(body.path("ci_code").asText()).isEmpty();
            var actor =
                    new InternalActorTokens(SECRET)
                            .verify(request.getHeader("Authorization"), "platform");
            assertThat(actor.targetCode()).isEqualTo("alert-target-resolution");
            assertThat(actor.roles()).containsExactly("SYSTEM");
            assertThat(actor.runId()).isEqualTo("a".repeat(64));
        }
    }

    @Test
    void outageOrInconsistentResponseReturns503SoWebhookCanRetry() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    """
{"code":0,"data":{"status":"MATCHED","targetCode":"ops-agent-service",
"environment":"","message":"missing environment"}}
"""));
            var client = new AlertTargetClient(SECRET, server.url("/").toString());
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> client.resolve(json.createObjectNode(), "a".repeat(64)))
                        .isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("503");
            }
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }
}
