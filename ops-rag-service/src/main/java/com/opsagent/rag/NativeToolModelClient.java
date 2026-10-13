package com.opsagent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 单次原生工具协议适配；不执行工具、不解析正文中的调用意图、不续写或隐式重试。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class NativeToolModelClient {
    private final AiProperties properties;
    private final AiHttpExecutor http;
    private final ObjectMapper mapper;

    NativeToolModelClient(AiProperties properties, AiHttpExecutor http, ObjectMapper mapper) {
        this.properties = properties;
        this.http = http;
        this.mapper = mapper;
    }

    void validate(InternalAgentDtos.TurnRequest request) {
        if (request.messages() == null
                || request.messages().isEmpty()
                || request.messages().size() > 64
                || request.tools() == null
                || request.tools().size() > 20
                || request.maxOutputTokens() < 1
                || request.maxOutputTokens() > 8192
                || request.remainingTokens() < 1
                || request.remainingTokens() > 100000) throw invalid();
        List<Map<String, Object>> tools = tools(request);
        messages(request);
        int inputUpperBound = inputTokenUpperBound(request, tools);
        if (inputUpperBound > 32768
                || inputUpperBound + request.maxOutputTokens() > request.remainingTokens()) {
            throw new BusinessException(ErrorCode.VALIDATION, "MODEL_TOKEN_BUDGET_EXCEEDED");
        }
    }

    int reservation(InternalAgentDtos.TurnRequest request) {
        return inputTokenUpperBound(request, tools(request))
                + Math.min(request.maxOutputTokens(), properties.getMaxOutputTokens());
    }

    private int inputTokenUpperBound(InternalAgentDtos.TurnRequest request, List<Map<String, Object>> tools) {
        return
                json(Map.of("messages", request.messages(), "tools", tools))
                                .getBytes(StandardCharsets.UTF_8)
                                .length
                        + 512
                        + request.messages().size() * 64;
    }

    InternalAgentDtos.TurnResponse call(InternalAgentDtos.TurnRequest request, String forcedTool) {
        return call(request, forcedTool, Duration.ofSeconds(Math.min(properties.getTimeoutSeconds(), 55)));
    }

    InternalAgentDtos.TurnResponse call(
            InternalAgentDtos.TurnRequest request, String forcedTool, Duration timeout) {
        validate(request);
        var settings = properties.settings(request.provider());
        List<Map<String, Object>> normalizedTools = tools(request);
        List<Map<String, Object>> normalizedMessages = messages(request);
        boolean responses = "responses".equals(settings.getApiStyle());
        if (!responses && !"chat-completions".equals(settings.getApiStyle())) {
            throw new BusinessException(ErrorCode.CONFLICT, "MODEL_PROTOCOL_UNSUPPORTED");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        if (!normalizedTools.isEmpty()) body.put("parallel_tool_calls", false);
        int outputLimit = Math.min(request.maxOutputTokens(), properties.getMaxOutputTokens());
        if (outputLimit < 1) throw invalid();
        if (responses) {
            body.put("input", responseInput(normalizedMessages));
            body.put("max_output_tokens", outputLimit);
            body.put("reasoning", Map.of("effort", "none"));
            body.put("store", false);
            if (!normalizedTools.isEmpty()) {
                body.put(
                        "tools",
                        normalizedTools.stream()
                                .map(
                                        tool -> {
                                            Map<String, Object> function =
                                                    new LinkedHashMap<>(
                                                            object(tool.get("function")));
                                            function.put("type", "function");
                                            return function;
                                        })
                                .toList());
                body.put(
                        "tool_choice",
                        forcedTool == null
                                ? "auto"
                                : Map.of("type", "function", "name", forcedTool));
            }
        } else {
            body.put("messages", normalizedMessages);
            body.put("max_tokens", outputLimit);
            if (!normalizedTools.isEmpty()) {
                body.put("tools", normalizedTools);
                body.put(
                        "tool_choice",
                        forcedTool == null
                                ? "auto"
                                : Map.of(
                                        "type",
                                        "function",
                                        "function",
                                        Map.of("name", forcedTool)));
            }
            if (Set.of("deepseek", "kimi").contains(request.provider())) {
                body.put("thinking", Map.of("type", "disabled"));
            }
        }
        long started = System.nanoTime();
        JsonNode response =
                http.postBounded(
                        request.provider(),
                        settings.getBaseUrl(),
                        responses ? "/responses" : "/chat/completions",
                        settings.getApiKey(),
                        body,
                        timeout);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return parse(request, response, responses, elapsed);
    }

    private List<Map<String, Object>> tools(InternalAgentDtos.TurnRequest request) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Map<String, Object> tool : request.tools()) {
            if (tool == null || !"function".equals(tool.get("type"))) throw invalid();
            Map<String, Object> fn = object(tool.get("function"));
            String name = string(fn.get("name"));
            if (!name.matches("[A-Za-z0-9_-]{1,64}") || !names.add(name)) throw invalid();
            String description = fn.get("description") == null ? "" : string(fn.get("description"));
            JsonNode schema = mapper.valueToTree(fn.get("parameters"));
            if (description.length() > 2000
                    || schema == null
                    || !schema.isObject()
                    || !"object".equals(schema.path("type").asText())
                    || !schema.path("properties").isObject()
                    || !schema.findValues("$ref").isEmpty()
                    || json(schema).length() > 12000) throw invalid();
            result.add(
                    Map.of(
                            "type",
                            "function",
                            "function",
                            Map.of(
                                    "name",
                                    name,
                                    "description",
                                    description,
                                    "parameters",
                                    schema)));
        }
        return result;
    }

    private List<Map<String, Object>> messages(InternalAgentDtos.TurnRequest request) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> pending = new HashSet<>();
        Set<String> allIds = new HashSet<>();
        for (Map<String, Object> original : request.messages()) {
            if (original == null) throw invalid();
            String role = string(original.get("role"));
            if (!Set.of("system", "user", "assistant", "tool").contains(role)) throw invalid();
            if (!pending.isEmpty() && !"tool".equals(role)) throw invalid();
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", role);
            Object content = original.get("content");
            if (content != null && !(content instanceof String)) throw invalid();
            message.put("content", content);
            if ("tool".equals(role)) {
                String id = string(original.get("tool_call_id"));
                if (!pending.remove(id) || content == null) throw invalid();
                message.put("tool_call_id", id);
            } else if ("assistant".equals(role) && original.containsKey("tool_calls")) {
                List<Map<String, Object>> calls =
                        calls(mapper.valueToTree(original.get("tool_calls")), null);
                for (Map<String, Object> call : calls) {
                    String id = string(call.get("id"));
                    if (!allIds.add(id)) throw invalid();
                    pending.add(id);
                }
                message.put("tool_calls", calls);
            } else if (content == null) throw invalid();
            result.add(message);
        }
        if (!pending.isEmpty()) throw invalid();
        return result;
    }

    private List<Map<String, Object>> responseInput(List<Map<String, Object>> messages) {
        List<Map<String, Object>> input = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            String role = string(message.get("role"));
            if ("tool".equals(role)) {
                input.add(
                        Map.of(
                                "type",
                                "function_call_output",
                                "call_id",
                                message.get("tool_call_id"),
                                "output",
                                message.get("content")));
            } else {
                if (message.get("content") != null && !string(message.get("content")).isEmpty()) {
                    input.add(Map.of("role", role, "content", message.get("content")));
                }
                JsonNode toolCalls = mapper.valueToTree(message.get("tool_calls"));
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode call : toolCalls) {
                        input.add(
                                Map.of(
                                        "type",
                                        "function_call",
                                        "call_id",
                                        call.path("id").asText(),
                                        "name",
                                        call.path("function").path("name").asText(),
                                        "arguments",
                                        call.path("function").path("arguments").asText()));
                    }
                }
            }
        }
        return input;
    }

    private InternalAgentDtos.TurnResponse parse(
            InternalAgentDtos.TurnRequest request,
            JsonNode response,
            boolean responses,
            long elapsed) {
        if (response == null || response.hasNonNull("error")) {
            throw new AiProviderException(request.provider(), 502, "MODEL_RESPONSE_INVALID", null,
                    AiProviderException.FailureKind.PROTOCOL);
        }
        String finish;
        String content = "";
        JsonNode rawCalls = null;
        boolean refused = false;
        if (responses) {
            finish = response.path("status").asText("unknown");
            List<Map<String, Object>> extracted = new ArrayList<>();
            for (JsonNode item : response.path("output")) {
                String type = item.path("type").asText();
                if ("function_call".equals(type)) {
                    extracted.add(
                            Map.of(
                                    "id",
                                    item.path("call_id").asText(),
                                    "type",
                                    "function",
                                    "function",
                                    Map.of(
                                            "name",
                                            item.path("name").asText(),
                                            "arguments",
                                            item.path("arguments").asText())));
                } else if ("message".equals(type)) {
                    for (JsonNode part : item.path("content")) {
                        if ("output_text".equals(part.path("type").asText()))
                            content += part.path("text").asText("");
                        if ("refusal".equals(part.path("type").asText())) refused = true;
                    }
                } else if ("reasoning".equals(type)) {
                    // This contract intentionally excludes private reasoning state and cannot
                    // replay it.
                    finish = "reasoning_state_unsupported";
                }
            }
            rawCalls = mapper.valueToTree(extracted);
        } else {
            JsonNode choice = response.path("choices").path(0);
            finish = choice.path("finish_reason").asText("unknown");
            JsonNode message = choice.path("message");
            content = message.path("content").asText("");
            rawCalls = message.path("tool_calls");
            refused = message.hasNonNull("refusal") || "content_filter".equals(finish);
        }
        String outcome = "INCOMPLETE";
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", content.isEmpty() ? null : content);
        boolean complete =
                responses
                        ? "completed".equals(finish)
                        : Set.of("stop", "tool_calls").contains(finish);
        if (refused) outcome = "REFUSED";
        else if (complete && rawCalls != null && rawCalls.isArray() && !rawCalls.isEmpty()) {
            try {
                Set<String> allowed = new HashSet<>();
                request.tools()
                        .forEach(
                                tool ->
                                        allowed.add(
                                                string(object(tool.get("function")).get("name"))));
                assistant.put("tool_calls", calls(rawCalls, allowed));
                outcome = "TOOL_CALLS";
            } catch (BusinessException invalidCalls) {
                finish = "invalid_tool_calls";
            }
        } else if (complete && !content.isBlank() && (responses || "stop".equals(finish)))
            outcome = "FINAL";
        JsonNode usage = response.path("usage");
        Integer input = count(usage.path(responses ? "input_tokens" : "prompt_tokens"));
        Integer output = count(usage.path(responses ? "output_tokens" : "completion_tokens"));
        Integer total = count(usage.path("total_tokens"));
        if (total == null
                && input != null
                && output != null
                && (long) input + output <= Integer.MAX_VALUE) {
            total = input + output;
        }
        boolean known =
                input != null && output != null && total != null && total >= (long) input + output;
        return new InternalAgentDtos.TurnResponse(
                outcome,
                assistant,
                input,
                output,
                total,
                known,
                request.provider(),
                response.path("model").asText(request.model()),
                finish,
                elapsed);
    }

    private List<Map<String, Object>> calls(JsonNode raw, Set<String> allowed) {
        if (raw == null || !raw.isArray() || raw.isEmpty() || raw.size() > 20) throw invalid();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode call : raw) {
            String id = call.path("id").asText();
            String name = call.path("function").path("name").asText();
            JsonNode args = call.path("function").path("arguments");
            if (!"function".equals(call.path("type").asText())
                    || !id.matches("[A-Za-z0-9_-]{1,160}")
                    || !seen.add(id)
                    || !name.matches("[A-Za-z0-9_-]{1,64}")
                    || (allowed != null && !allowed.contains(name))
                    || !args.isTextual()
                    || args.asText().length() > 16000) {
                throw invalid();
            }
            try {
                JsonNode parsed =
                        mapper.reader()
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readTree(args.asText());
                if (parsed == null || !parsed.isObject()) throw invalid();
            } catch (JsonProcessingException exception) {
                throw invalid();
            }
            result.add(
                    Map.of(
                            "id",
                            id,
                            "type",
                            "function",
                            "function",
                            Map.of("name", name, "arguments", args.asText())));
        }
        return result;
    }

    private Integer count(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0
                ? value.intValue()
                : null;
    }

    private Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw invalid();
        return mapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private String string(Object value) {
        if (!(value instanceof String text)) throw invalid();
        return text;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION, "INVALID_NATIVE_TOOL_PAYLOAD");
    }
}
