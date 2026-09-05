package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 检查供应商结束原因，只对明确的长度截断执行有限续写。
 *
 * @author heyu
 * @since 2026/9/3
 */
public abstract class ChatCompletionsLlmClient implements LlmClient {
    private static final String CONTINUE_PROMPT =
            "上一条回答因输出长度上限中断。请紧接最后一个字符继续完成原问题的回答，"
                    + "不要重复已有内容，不要重新开始，不要解释续写过程。"
                    + "保留原有章节和引用编号，优先完整收尾。";
    private final AiProperties properties;
    private final AiHttpExecutor http;
    private final AiStreamHttpExecutor streamHttp;

    ChatCompletionsLlmClient(
            AiProperties properties, AiHttpExecutor http, AiStreamHttpExecutor streamHttp) {
        this.properties = properties;
        this.http = http;
        this.streamHttp = streamHttp;
    }

    @Override
    public boolean configured() {
        return properties.isEnabled() && settings().configured();
    }

    @Override
    public String model() {
        return settings().getModel();
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        return execute(request, null);
    }

    @Override
    public LlmResult stream(LlmRequest request, Consumer<String> onDelta) {
        return execute(request, onDelta);
    }

    private LlmResult execute(LlmRequest request, Consumer<String> onDelta) {
        if (!configured()) {
            throw new AiProviderException(provider(), 0, "当前 AI 服务未完成配置，请联系管理员。", null);
        }
        StringBuilder answer = new StringBuilder();
        int inputTokens = 0;
        int outputTokens = 0;
        int continuation = 0;
        for (;;) {
            Turn turn = new Turn();
            Map<String, Object> body = body(request, answer.toString(), onDelta != null);
            String finishReason;
            try {
                if (onDelta == null) {
                    JsonNode response = http.post(
                            provider(), settings().getBaseUrl(), "/chat/completions",
                            settings().getApiKey(), body, properties.getTimeoutSeconds(),
                            properties.getMaximumAttempts());
                    JsonNode choice = response == null ? null : response.path("choices").path(0);
                    if (choice == null) {
                        throw new AiProviderException(provider(), 502, "AI 服务返回了空回答。", null);
                    }
                    turn.text.append(choice.path("message").path("content").asText(""));
                    turn.finishReason = choice.path("finish_reason").asText("unknown");
                    turn.usage(response.path("usage"));
                    answer.append(turn.text);
                    finishReason = turn.finishReason;
                } else {
                    boolean done = streamHttp.post(
                            provider(), settings().getBaseUrl(), "/chat/completions",
                            settings().getApiKey(), body, properties.getTimeoutSeconds(),
                            properties.getMaximumAttempts(),
                            event -> handleEvent(event, turn, answer, onDelta));
                    finishReason = done && !turn.finishReason.equals("unknown")
                            ? turn.finishReason : "stream_interrupted";
                }
            } catch (AiProviderException exception) {
                if (answer.isEmpty()) throw exception;
                return result(answer, inputTokens + turn.inputTokens,
                        outputTokens + turn.outputTokens, false,
                        onDelta == null ? "continuation_failed" : "stream_interrupted", continuation);
            }
            inputTokens += turn.inputTokens;
            outputTokens += turn.outputTokens;
            if (answer.isEmpty()) {
                throw new AiProviderException(provider(), 502, "AI 服务返回了空回答。", null);
            }
            if (turn.text.isEmpty()) {
                return result(answer, inputTokens, outputTokens,
                        false, "empty_continuation", continuation);
            }
            if ("length".equals(finishReason)
                    && !turn.text.isEmpty()
                    && continuation < properties.getMaximumContinuations()) {
                continuation++;
                continue;
            }
            return result(answer, inputTokens, outputTokens,
                    "stop".equals(finishReason), finishReason, continuation);
        }
    }

    private Map<String, Object> body(LlmRequest request, String previous, boolean streaming) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        messages.add(Map.of("role", "user", "content", request.userPrompt()));
        if (!previous.isEmpty()) {
            messages.add(Map.of("role", "assistant", "content", previous));
            messages.add(Map.of("role", "user", "content", CONTINUE_PROMPT));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("messages", messages);
        body.put("max_tokens", Math.max(1, Math.min(request.maxOutputTokens(), 32768)));
        if (streaming) {
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
        }
        body.putAll(additionalBody());
        return body;
    }

    private boolean handleEvent(
            JsonNode event, Turn turn, StringBuilder answer, Consumer<String> onDelta) {
        if (event.hasNonNull("error")) {
            throw new AiProviderException(provider(), 502, "AI 供应商中断了本次生成。", null);
        }
        turn.usage(event.path("usage"));
        JsonNode choice = event.path("choices").path(0);
        if (choice.hasNonNull("finish_reason")) {
            turn.finishReason = choice.path("finish_reason").asText("unknown");
        }
        JsonNode delta = choice.path("delta").path("content");
        if (!delta.isTextual() || delta.textValue().isEmpty()) return false;
        turn.text.append(delta.textValue());
        answer.append(delta.textValue());
        onDelta.accept(delta.textValue());
        return true;
    }

    private LlmResult result(
            StringBuilder answer, int inputTokens, int outputTokens,
            boolean complete, String finishReason, int continuation) {
        return new LlmResult(answer.toString().trim(), provider(), model(),
                inputTokens, outputTokens, complete, finishReason, continuation);
    }

    private AiProperties.ProviderSettings settings() {
        return properties.settings(provider());
    }

    protected Map<String, Object> additionalBody() {
        return Map.of();
    }

    /**
     * 保存单次供应商生成的文本、终止状态和用量。
     *
     * @author heyu
     * @since 2026/9/3
     */
    private static final class Turn {
        private final StringBuilder text = new StringBuilder();
        private String finishReason = "unknown";
        private int inputTokens;
        private int outputTokens;

        private void usage(JsonNode usage) {
            if (usage.isMissingNode() || usage.isNull()) return;
            inputTokens = usage.path("prompt_tokens").asInt();
            outputTokens = usage.path("completion_tokens").asInt();
        }
    }
}
