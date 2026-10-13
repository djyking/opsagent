package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.regex.Pattern;

/**
 * 模型失败的展示与人工恢复合同；持久未知请求不能经继续按钮重新发送。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AgentModelFailure {
    private static final Pattern CODE = Pattern.compile("(?:MODEL_[A-Z0-9_]+|INVALID_NATIVE_TOOL_PAYLOAD)");

    private AgentModelFailure() {}

    static ObjectNode fromState(JsonNode state) {
        if (state.path("modelFailure").isObject()) return (ObjectNode) state.path("modelFailure").deepCopy();
        String message = state.path("message").asText();
        if (state.has("modelIntent") && CODE.matcher(message).find()) return fromMessage(message);
        return null;
    }

    static ObjectNode fromMessage(String message) {
        var matcher = CODE.matcher(message == null ? "" : message);
        String code = matcher.find() ? matcher.group() : "MODEL_TRANSPORT_UNKNOWN";
        boolean configuration = code.equals("MODEL_HTTP_401") || code.equals("MODEL_HTTP_403")
                || code.equals("MODEL_HTTP_400") || code.equals("MODEL_HTTP_422")
                || code.contains("CONFIGURED") || code.contains("SNAPSHOT") || code.contains("VERIFIED")
                || code.contains("PROTOCOL") || code.contains("INVALID");
        String reason = message;
        if (reason == null || !reason.matches("(?s).*[\\u4e00-\\u9fff].*")) {
            reason = "本轮模型决策没有可确认的回执，旧记录未保留底层错误原因；"
                    + "原调用不会重复发送，请核对模型配置后启动新的隔离演练。";
        } else if (code.equals("MODEL_TRANSPORT_UNKNOWN")) {
            reason = "模型调用链未取得可确认的响应；原调用不会重复发送，请检查服务连通性后启动新的隔离演练。";
        }
        return AgentJson.object().put("code", code).put("reason", reason)
                .put("canResume", false).put("retryable", false)
                .put("recoveryAction", configuration ? "CHECK_CONFIGURATION" : "NEW_RUN");
    }
}
