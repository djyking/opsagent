package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 检查 Responses API 的 completed/incomplete 终态并保留未完成回答。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class OpenAiLlmClient implements LlmClient {
    private final AiProperties properties;
    private final AiHttpExecutor http;
    private final AiStreamHttpExecutor streamHttp;

    OpenAiLlmClient(
            AiProperties properties, AiHttpExecutor http, AiStreamHttpExecutor streamHttp) {
        this.properties = properties;
        this.http = http;
        this.streamHttp = streamHttp;
    }

    @Override
    public String provider() { return "openai"; }

    @Override
    public boolean configured() { return properties.isEnabled() && settings().configured(); }

    @Override
    public String model() { return settings().getModel(); }

    @Override
    public LlmResult generate(LlmRequest request) { return execute(request, null); }

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
            try {
                if (onDelta == null) {
                    JsonNode response = http.post(
                            provider(), settings().getBaseUrl(), "/responses", settings().getApiKey(),
                            body, properties.getTimeoutSeconds(), properties.getMaximumAttempts());
                    if (response == null) {
                        throw new AiProviderException(provider(), 502, "AI 服务返回了空回答。", null);
                    }
                    String text = response.path("output_text").asText("");
                    if (text.isBlank()) {
                        text = response.path("output").findValues("text").stream()
                                .map(JsonNode::asText).filter(value -> !value.isBlank())
                                .reduce("", (left, right) -> left + right);
                    }
                    turn.text.append(text);
                    answer.append(text);
                    turn.complete(response);
                } else {
                    streamHttp.post(
                            provider(), settings().getBaseUrl(), "/responses", settings().getApiKey(),
                            body, properties.getTimeoutSeconds(), properties.getMaximumAttempts(),
                            event -> handleEvent(event, turn, answer, onDelta));
                    if (!turn.terminal) turn.finishReason = "stream_interrupted";
                }
            } catch (AiProviderException exception) {
                if (answer.isEmpty()) throw exception;
                return result(answer, inputTokens + turn.inputTokens, outputTokens + turn.outputTokens,
                        onDelta == null ? "continuation_failed" : "stream_interrupted", continuation);
            }
            inputTokens += turn.inputTokens;
            outputTokens += turn.outputTokens;
            if (answer.isEmpty()) {
                throw new AiProviderException(provider(), 502, "AI 服务返回了空回答。", null);
            }
            if (turn.text.isEmpty()) {
                return result(answer, inputTokens, outputTokens, "empty_continuation", continuation);
            }
            if ("length".equals(turn.finishReason) && continuation < properties.getMaximumContinuations()) {
                continuation++;
                continue;
            }
            return result(answer, inputTokens, outputTokens, turn.finishReason, continuation);
        }
    }

    private Map<String, Object> body(LlmRequest request, String previous, boolean streaming) {
        List<Map<String, String>> input = new ArrayList<>();
        input.add(Map.of("role", "system", "content", request.systemPrompt()));
        input.add(Map.of("role", "user", "content", request.userPrompt()));
        if (!previous.isEmpty()) {
            input.add(Map.of("role", "assistant", "content", previous));
            input.add(Map.of("role", "user", "content",
                    "上一条回答因长度上限中断，请紧接最后一个字符继续并完整收尾。"
                            + "不要重复已有内容，不要重新开始，不要解释续写过程，保留引用编号。"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("input", input);
        body.put("max_output_tokens", Math.max(1, Math.min(request.maxOutputTokens(), 32768)));
        body.put("reasoning", Map.of("effort", "low"));
        if (streaming) body.put("stream", true);
        return body;
    }

    private boolean handleEvent(
            JsonNode event, Turn turn, StringBuilder answer, Consumer<String> onDelta) {
        String type = event.path("type").asText();
        if ("response.output_text.delta".equals(type)) {
            JsonNode delta = event.path("delta");
            if (!delta.isTextual() || delta.textValue().isEmpty()) return false;
            turn.text.append(delta.textValue());
            answer.append(delta.textValue());
            onDelta.accept(delta.textValue());
            return true;
        }
        if ("response.completed".equals(type) || "response.incomplete".equals(type)
                || "response.failed".equals(type)) {
            turn.complete(event.path("response"));
        } else if ("error".equals(type)) {
            throw new AiProviderException(provider(), 502, "AI 供应商中断了本次生成。", null);
        }
        return false;
    }

    private LlmResult result(
            StringBuilder answer, int inputTokens, int outputTokens, String reason, int continuation) {
        return new LlmResult(answer.toString().trim(), provider(), model(), inputTokens,
                outputTokens, "stop".equals(reason), reason, continuation);
    }

    private AiProperties.ProviderSettings settings() { return properties.settings(provider()); }

    /**
     * 保存 Responses API 终止事件和用量，不把单个文本块结束视为完整回答。
     *
     * @author heyu
     * @since 2026/9/3
     */
    private static final class Turn {
        private final StringBuilder text = new StringBuilder();
        private boolean terminal;
        private String finishReason = "unknown";
        private int inputTokens;
        private int outputTokens;

        private void complete(JsonNode response) {
            String status = response.path("status").asText();
            terminal = List.of("completed", "incomplete", "failed").contains(status);
            finishReason = switch (status) {
                case "completed" -> "stop";
                case "failed" -> "provider_error";
                case "incomplete" -> {
                    String reason = response.path("incomplete_details").path("reason").asText("unknown");
                    yield "max_output_tokens".equals(reason) ? "length" : reason;
                }
                default -> "unknown";
            };
            inputTokens = response.path("usage").path("input_tokens").asInt();
            outputTokens = response.path("usage").path("output_tokens").asInt();
        }
    }
}
