package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个问答共享同一账本，续写和网络重试均先预留；未知用量不作为零释放。 UTF-8 字节数加协议余量是保守上界，不向用户冒充供应商精确 token 数。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AssistantTokenBudget implements AutoCloseable {
    static final int LIMIT = 50_000;
    private static final ThreadLocal<AssistantTokenBudget> CURRENT = new ThreadLocal<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AssistantTokenBudget previous;
    private java.util.function.Consumer<AssistantTokenBudget> audit;
    private int charged;
    private int attempts;
    private boolean usageKnown = true;

    private AssistantTokenBudget(int priorReservedTokens) {
        if (priorReservedTokens < 0 || priorReservedTokens > LIMIT) {
            throw new IllegalArgumentException("本次问答的先前预留额度无效");
        }
        charged = priorReservedTokens;
        usageKnown = priorReservedTokens == 0;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static AssistantTokenBudget open() {
        return new AssistantTokenBudget(0);
    }

    static AssistantTokenBudget open(java.util.function.Consumer<AssistantTokenBudget> audit) {
        return open(0, audit);
    }

    static AssistantTokenBudget open(
            int priorReservedTokens, java.util.function.Consumer<AssistantTokenBudget> audit) {
        var budget = new AssistantTokenBudget(priorReservedTokens);
        budget.audit = audit;
        return budget;
    }

    static Reservation reserve(String provider, Map<String, Object> original) {
        AssistantTokenBudget budget = CURRENT.get();
        // Retrieval embeddings were reserved before search; independent Agent calls have their own
        // ledger.
        if (budget == null
                || (!original.containsKey("max_tokens")
                        && !original.containsKey("max_output_tokens")))
            return new Reservation(null, original, 0);
        int input = upperBound(original);
        int remaining = LIMIT - budget.charged - input;
        if (remaining < 64)
            throw new AiProviderException(
                    provider,
                    0,
                    "本次回答的输入、输出及重试累计额度为 50,000 token，剩余额度不足，未发送新请求。请缩小问题范围或减少附件。",
                    null,
                    AiProviderException.FailureKind.BUDGET);
        String key = original.containsKey("max_tokens") ? "max_tokens" : "max_output_tokens";
        int desired = ((Number) original.get(key)).intValue();
        Map<String, Object> body = new LinkedHashMap<>(original);
        int output = Math.max(1, Math.min(desired, remaining));
        body.put(key, output);
        budget.charged += input + output;
        budget.attempts++;
        return new Reservation(budget, body, input + output);
    }

    static int upperBound(Map<String, Object> body) {
        try {
            return JSON.writeValueAsBytes(body).length + 512;
        } catch (Exception error) {
            throw new IllegalArgumentException("无法计算模型请求预算", error);
        }
    }

    static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static int promptUpperBound(LlmRequest request) {
        return upperBound(
                        Map.of(
                                "model",
                                "provider-model-placeholder",
                                "messages",
                                java.util.List.of(
                                        Map.of("role", "system", "content", request.systemPrompt()),
                                        Map.of("role", "user", "content", request.userPrompt())),
                                "max_tokens",
                                request.maxOutputTokens()))
                + 256;
    }

    static String recentHistory(String history, int maximumBytes) {
        if (history == null || maximumBytes <= 0) return "";
        String result = history;
        // Remove complete oldest lines, preserving the current question in its separate request
        // field.
        while (bytes(result) > maximumBytes) {
            int end = result.indexOf('\n');
            if (end < 0) return "";
            result = result.substring(end + 1);
        }
        return result;
    }

    int charged() {
        return charged;
    }

    int attempts() {
        return attempts;
    }

    boolean usageKnown() {
        return usageKnown;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
        if (audit != null) audit.accept(this);
    }

    static final class Reservation {
        private final AssistantTokenBudget budget;
        private final Map<String, Object> body;
        private final int reserved;
        private boolean settled;

        Reservation(AssistantTokenBudget budget, Map<String, Object> body, int reserved) {
            this.budget = budget;
            this.body = body;
            this.reserved = reserved;
        }

        Map<String, Object> body() {
            return body;
        }

        void finish(JsonNode usage) {
            if (settled || budget == null) return;
            settled = true;
            if (usage != null && usage.isObject()) {
                boolean chat = usage.has("prompt_tokens") || usage.has("completion_tokens");
                Integer input = exactCount(usage.path(chat ? "prompt_tokens" : "input_tokens"));
                Integer output =
                        exactCount(usage.path(chat ? "completion_tokens" : "output_tokens"));
                if (input != null && output != null) {
                    long actual = (long) input + output;
                    boolean valid = actual <= Integer.MAX_VALUE;
                    if (usage.has("total_tokens")) {
                        Integer total = exactCount(usage.path("total_tokens"));
                        valid &= total != null && total >= actual;
                        if (total != null) actual = Math.max(actual, total.longValue());
                    }
                    // Provider completion/output counts already include reasoning tokens;
                    // adding their nested detail again would double-charge the same generation.
                    long charged = (long) budget.charged + actual - reserved;
                    if (valid && charged >= 0 && charged <= Integer.MAX_VALUE) {
                        // Keep genuine over-limit usage intact; the next reservation is blocked.
                        budget.charged = (int) charged;
                        return;
                    }
                }
            }
            budget.usageKnown = false;
        }

        private static Integer exactCount(JsonNode value) {
            return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0
                    ? value.intValue()
                    : null;
        }
    }
}
