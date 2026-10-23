package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 对已知模型结果中的未执行诊断参数给出有界反馈，保留原结果与计费，不修改修复动作。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AgentDiagnosticCorrections {
    static final int MAX_CORRECTIONS = 2;

    private AgentDiagnosticCorrections() {}

    static ObjectNode correct(
            AgentStore.Run run, JsonNode response, ArrayNode calls, String reason) {
        ObjectNode state = run.state();
        if (!state.has("modelIntent")
                || state.has("modelFailure")
                || state.has("toolIntent")
                || !state.path("pendingCalls").isEmpty()
                || !state.path("messages").isArray()
                || !response.path("outcome").asText().equals("TOOL_CALLS")
                || calls.isEmpty()
                || calls.size() > 4
                || !calls.path(0).path("name").asText().equals("ticket_add_analysis"))
            return null;
        for (JsonNode call : calls) {
            if (call.has("preparedRequest") || call.path("graphTool").asBoolean()) return null;
        }
        int count = state.path("diagnosticParameterCorrections").asInt();
        ObjectNode audit =
                AgentJson.object()
                        .put("attempt", count + 1)
                        .put("maximum", MAX_CORRECTIONS)
                        .put("reason", reason)
                        .put("writePrepared", false)
                        .put("tokensAlreadyCharged", state.path("tokens").asInt())
                        .put("callId", state.path("modelIntent").path("callId").asText())
                        .put("exhausted", count >= MAX_CORRECTIONS);
        audit.set("originalModelResponse", response.deepCopy());
        audit.set("unexecutedCalls", calls.deepCopy());
        audit.put("frozenConstraints", policy(run));
        if (count >= MAX_CORRECTIONS) return audit;

        ArrayNode messages = (ArrayNode) state.path("messages");
        messages.add(response.path("assistantMessage").deepCopy());
        for (JsonNode call : calls) {
            String feedback =
                    call.path("name").asText().equals("ticket_add_analysis")
                            ? "DIAGNOSTIC_ARGUMENTS_INVALID："
                                    + reason
                                    + "。诊断未写入；按照以下冻结约束缩短或纠正参数后重新提出诊断。"
                                    + policy(run)
                                    + "不截断证据ID、不编造事实、不改变权限。其他同批调用全部取消，未执行、未创建审批。"
                            : "CANCELLED_BEFORE_EXECUTION：同批诊断参数校验失败；本工具未执行、未创建审批。"
                                    + "先纠正诊断，再根据新鲜证据重新提出动作。";
            messages.add(
                    AgentJson.object()
                            .put("role", "tool")
                            .put("tool_call_id", call.path("providerCallId").asText())
                            .put("content", feedback));
        }
        state.put("diagnosticParameterCorrections", count + 1);
        state.put("referenceCorrectionPending", true);
        state.remove("modelIntent");
        state.putArray("pendingCalls");
        return audit;
    }

    static String policy(AgentStore.Run run) {
        JsonNode properties =
                AgentTools.frozenParameters(run, "ticket_add_analysis").path("properties");
        StringBuilder policy = new StringBuilder("ticket_add_analysis 每个字段分别限制：");
        properties.fields().forEachRemaining(field -> {
            if (field.getValue().has("maxLength"))
                policy.append(field.getKey()).append(" maxLength=")
                        .append(field.getValue().path("maxLength").asInt()).append("；");
        });
        if (properties.path("evidence").has("maxLength")) {
            int target = Math.min(500, properties.path("evidence").path("maxLength").asInt());
            policy.append("evidence 建议控制在 ").append(target)
                    .append(" 字符以内留出余量，优先选能支持结论的少量关键测量，")
                    .append("不复制完整证据包或全部指标。每条保留完整 ev- ID、关键路径、原值和单位；")
                    .append("无需重复完整 revision、来源标题及无关指标。仅引用实际使用的条目。")
                    .append("不得删改原值或截断证据ID。");
            if (properties.has("candidateCauses") && properties.has("evidenceGaps"))
                policy.append("解释写 candidateCauses，缺口写 evidenceGaps。");
            else policy.append("解释与缺口在冻结协议已有的 summary、recommendation 中区分。");
            policy.append('\n');
        }
        return policy.toString();
    }
}
