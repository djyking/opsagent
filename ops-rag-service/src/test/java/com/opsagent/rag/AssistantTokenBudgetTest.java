package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.QueryEmbeddingBudget;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * @author heyu
 * @since 2026/9/3
 */
class AssistantTokenBudgetTest {
    private final ObjectMapper json = new ObjectMapper();

    private Map<String, Object> request(int output) {
        return Map.of(
                "model",
                "test",
                "messages",
                java.util.List.of(Map.of("role", "user", "content", "测试")),
                "max_tokens",
                output);
    }

    @Test
    void reservesInputAndOutputAndBlocksRetryAfterUnknownUsage() {
        try (var budget = AssistantTokenBudget.open()) {
            var first = AssistantTokenBudget.reserve("deepseek", request(20_000));
            assertThat((int) first.body().get("max_tokens")).isLessThan(10_000);
            first.finish(null);
            assertThat(budget.charged()).isEqualTo(10_000);
            assertThat(budget.usageKnown()).isFalse();
            assertThatThrownBy(() -> AssistantTokenBudget.reserve("deepseek", request(100)))
                    .isInstanceOf(AiProviderException.class)
                    .hasMessageContaining("未发送");
        }
    }

    @Test
    void continuationUsesActualCumulativeUsageWithoutReset() throws Exception {
        try (var budget = AssistantTokenBudget.open()) {
            var first = AssistantTokenBudget.reserve("deepseek", request(4096));
            first.finish(json.readTree("{\"prompt_tokens\":1400,\"completion_tokens\":1600}"));
            first.finish(null);
            assertThat(budget.charged()).isEqualTo(3000);
            var next = AssistantTokenBudget.reserve("deepseek", request(9000));
            assertThat((int) next.body().get("max_tokens")).isLessThan(7000);
            assertThat(budget.charged()).isEqualTo(10_000);
            assertThat(budget.attempts()).isEqualTo(2);
        }
        try (var nextAnswer = AssistantTokenBudget.open()) {
            assertThat(nextAnswer.charged()).isZero();
        }
    }

    @Test
    void missingOneUsageFieldDoesNotReleaseReservation() throws Exception {
        try (var budget = AssistantTokenBudget.open()) {
            var first = AssistantTokenBudget.reserve("deepseek", request(4096));
            int reserved = budget.charged();
            first.finish(json.readTree("{\"prompt_tokens\":20}"));
            assertThat(budget.charged()).isEqualTo(reserved);
            assertThat(budget.usageKnown()).isFalse();
        }
    }

    @Test
    void oversizeCurrentQuestionIsRejectedBeforeProviderRequest() {
        try (var budget = AssistantTokenBudget.open()) {
            assertThatThrownBy(
                            () ->
                                    AssistantTokenBudget.reserve(
                                            "deepseek",
                                            Map.of(
                                                    "max_tokens",
                                                    100,
                                                    "messages",
                                                    java.util.List.of(
                                                            Map.of("content", "甲".repeat(4000))))))
                    .isInstanceOf(AiProviderException.class);
            assertThat(budget.attempts()).isZero();
        }
    }

    @Test
    void invalidOrOverflowingUsageNeverReleasesReservedTokens() throws Exception {
        for (String usage :
                java.util.List.of(
                        "{\"prompt_tokens\":9223372036854775807,\"completion_tokens\":9223372036854775807}",
                        "{\"prompt_tokens\":2147483647,\"completion_tokens\":2147483647}",
                        "{\"prompt_tokens\":-1,\"completion_tokens\":50}",
                        "{\"prompt_tokens\":0.5,\"completion_tokens\":50}",
                        "{\"prompt_tokens\":\"10\",\"completion_tokens\":50}",
                        "{\"prompt_tokens\":10,\"completion_tokens\":50,\"total_tokens\":59}",
                        "{\"input_tokens\":10,\"output_tokens\":50,\"total_tokens\":null}",
                        "{\"input_tokens\":10,\"output_tokens\":50,\"total_tokens\":999999999999999999999}")) {
            try (var budget = AssistantTokenBudget.open()) {
                var reservation = AssistantTokenBudget.reserve("deepseek", request(4096));
                int before = budget.charged();
                reservation.finish(json.readTree(usage));
                assertThat(budget.charged()).as(usage).isEqualTo(before);
                assertThat(budget.usageKnown()).as(usage).isFalse();
            }
        }
    }

    @Test
    void reportedTotalAndReasoningDetailsAreCountedWithoutDoubleCharging() throws Exception {
        try (var budget = AssistantTokenBudget.open()) {
            var reservation = AssistantTokenBudget.reserve("deepseek", request(4096));
            reservation.finish(
                    json.readTree(
                            "{\"prompt_tokens\":100,\"completion_tokens\":200,\"total_tokens\":300,"
                                    + "\"completion_tokens_details\":{\"reasoning_tokens\":150}}"));
            assertThat(budget.charged()).isEqualTo(300);
            assertThat(budget.usageKnown()).isTrue();
            var continuation = AssistantTokenBudget.reserve("deepseek", request(4096));
            continuation.finish(
                    json.readTree(
                            "{\"input_tokens\":100,\"output_tokens\":200,\"total_tokens\":350}"));
            assertThat(budget.charged()).isEqualTo(650);
        }
    }

    @Test
    void realUsageAboveReservationIsPreservedAndBlocksAnotherRequest() throws Exception {
        try (var budget = AssistantTokenBudget.open()) {
            var reservation = AssistantTokenBudget.reserve("deepseek", request(4096));
            reservation.finish(
                    json.readTree(
                            "{\"prompt_tokens\":9000,\"completion_tokens\":3000,\"total_tokens\":12000}"));
            assertThat(budget.charged()).isEqualTo(12000);
            assertThat(budget.usageKnown()).isTrue();
            assertThatThrownBy(() -> AssistantTokenBudget.reserve("deepseek", request(64)))
                    .isInstanceOf(AiProviderException.class);
            assertThat(budget.attempts()).isEqualTo(1);
        }
    }

    @Test
    void retrievalReservationSurvivesActualGenerationUsageAndReducesRemainingOutput()
            throws Exception {
        int prior = QueryEmbeddingBudget.reserve("如何排查连接超时");
        try (var budget = AssistantTokenBudget.open(prior, null)) {
            assertThat(budget.charged()).isEqualTo(prior);
            assertThat(budget.usageKnown()).isFalse();
            var first = AssistantTokenBudget.reserve("deepseek", request(20_000));
            assertThat((int) first.body().get("max_tokens"))
                    .isEqualTo(10_000 - prior - AssistantTokenBudget.upperBound(request(20_000)));
            first.finish(json.readTree("{\"prompt_tokens\":100,\"completion_tokens\":200}"));
            assertThat(budget.charged()).isEqualTo(prior + 300);
            assertThat(budget.usageKnown()).isFalse();
            var retry = AssistantTokenBudget.reserve("deepseek", request(20_000));
            retry.finish(null);
            assertThat(budget.charged()).isEqualTo(10_000);
            assertThatThrownBy(() -> AssistantTokenBudget.reserve("deepseek", request(64)))
                    .isInstanceOf(AiProviderException.class);
        }
        assertThatThrownBy(() -> AssistantTokenBudget.open(-1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmRequest("system", "user", 100, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sharedEmbeddingReservationBoundsNormalizedSerializedQueriesAndEveryRetry()
            throws Exception {
        for (String query : java.util.List.of("普通查询", "\"\\\u0000😀", "㍿㍿㍿", "甲".repeat(2500))) {
            String normalized =
                    java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFKC)
                            .trim()
                            .replaceAll("\\s+", " ");
            normalized = normalized.substring(0, Math.min(2000, normalized.length()));
            int actual =
                    json.writeValueAsBytes(Map.of("input", java.util.List.of(normalized))).length;
            assertThat(QueryEmbeddingBudget.reserve(query))
                    .isGreaterThanOrEqualTo(3 * (actual + 512));
        }
        assertThat(QueryEmbeddingBudget.reserve("㍿"))
                .isEqualTo(QueryEmbeddingBudget.reserve("株式会社"));
        assertThat(QueryEmbeddingBudget.reserve("  a\n b  "))
                .isEqualTo(QueryEmbeddingBudget.reserve("a b"));
    }
}
