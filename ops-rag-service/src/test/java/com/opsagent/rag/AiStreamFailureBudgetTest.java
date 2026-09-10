package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Preserve real upstream failures when a later retry cannot reserve more budget.
 *
 * @author heyu
 * @since 2026/9/3
 */
class AiStreamFailureBudgetTest {
    private final AiStreamHttpExecutor executor = new AiStreamHttpExecutor(new ObjectMapper());

    @Test
    void twoRealHttpFailuresRemainVisibleWhenThirdReservationIsRejected() throws Exception {
        try (var server = new MockWebServer();
                var budget = AssistantTokenBudget.open(5046, null)) {
            server.enqueue(
                    new MockResponse().setResponseCode(503).setBody("PRIVATE_PROVIDER_BODY"));
            server.enqueue(
                    new MockResponse().setResponseCode(503).setBody("PRIVATE_PROVIDER_BODY"));
            server.start();
            Map<String, Object> body =
                    Map.of(
                            "model",
                            "test",
                            "max_tokens",
                            4096,
                            "messages",
                            List.of(Map.of("role", "user", "content", "x".repeat(16400))));
            var failure =
                    catchThrowableOfType(
                            () ->
                                    executor.post(
                                            "deepseek",
                                            server.url("/").toString(),
                                            "/chat/completions",
                                            "TEST_KEY_NEVER_LOG",
                                            body,
                                            3,
                                            3,
                                            event -> false),
                            AiProviderException.class);
            assertThat(failure.kind()).isEqualTo(AiProviderException.FailureKind.HTTP);
            assertThat(failure.statusCode()).isEqualTo(503);
            assertThat(failure.getMessage())
                    .contains("供应商")
                    .doesNotContain("50,000", "PRIVATE_PROVIDER_BODY");
            assertThat(server.getRequestCount()).isEqualTo(2);
            assertThat(budget.attempts()).isEqualTo(2);
            assertThat(budget.usageKnown()).isFalse();
            assertThat(budget.charged())
                    .isEqualTo(5046 + 2 * (AssistantTokenBudget.upperBound(body) + 4096));
        }
    }

    @Test
    void malformedSseRemainsProtocolFailureAndInitialBudgetDenialStillHasNoRequest()
            throws Exception {
        try (var server = new MockWebServer();
                var budget = AssistantTokenBudget.open()) {
            server.enqueue(
                    new MockResponse()
                            .addHeader("Content-Type", "text/event-stream")
                            .setBody("data: PRIVATE_INVALID_EVENT\n\n"));
            server.start();
            Map<String, Object> body =
                    Map.of(
                            "max_tokens",
                            100_000,
                            "messages",
                            List.of(Map.of("content", "question")));
            var failure =
                    catchThrowableOfType(
                            () ->
                                    executor.post(
                                            "deepseek",
                                            server.url("/").toString(),
                                            "/chat/completions",
                                            "TEST_KEY_NEVER_LOG",
                                            body,
                                            3,
                                            3,
                                            event -> false),
                            AiProviderException.class);
            assertThat(failure.kind()).isEqualTo(AiProviderException.FailureKind.PROTOCOL);
            assertThat(failure.diagnosticCode()).isEqualTo("INVALID_SSE_JSON");
            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(budget.charged()).isEqualTo(50_000);
            assertThat(budget.usageKnown()).isFalse();
            var rejected =
                    catchThrowableOfType(
                            () ->
                                    executor.post(
                                            "deepseek",
                                            server.url("/").toString(),
                                            "/chat/completions",
                                            "TEST_KEY_NEVER_LOG",
                                            body,
                                            3,
                                            3,
                                            event -> false),
                            AiProviderException.class);
            assertThat(rejected.kind()).isEqualTo(AiProviderException.FailureKind.BUDGET);
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }
}
