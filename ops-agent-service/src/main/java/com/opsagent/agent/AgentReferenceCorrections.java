package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 仅纠正尚未准备写请求的引用参数；原生调用逐一答复，不重放同批修复。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AgentReferenceCorrections {
    static final int MAX_CORRECTIONS = 2;

    private AgentReferenceCorrections() {}

    static boolean eligible(AgentStore.Run run) {
        JsonNode state = run.state();
        JsonNode call = state.path("toolIntent");
        if (state.has("modelIntent")
                || state.has("modelFailure")
                || !"ticket_add_analysis".equals(call.path("name").asText())
                || call.has("preparedRequest")
                || call.path("graphTool").asBoolean()
                || !state.path("messages").isArray()
                || !state.path("pendingCalls").isArray()
                || !call.path("id").equals(state.path("pendingCalls").path(0).path("id")))
            return false;
        java.util.Set<String> nativeIds = new java.util.HashSet<>();
        for (JsonNode message : state.path("messages")) {
            if (message.path("tool_calls").isArray()) {
                nativeIds.clear();
                message.path("tool_calls").forEach(raw -> nativeIds.add(raw.path("id").asText()));
            }
            if ("tool".equals(message.path("role").asText()))
                nativeIds.remove(message.path("tool_call_id").asText());
        }
        for (JsonNode pending : state.path("pendingCalls")) {
            if (pending.has("preparedRequest")
                    || pending.path("graphTool").asBoolean()
                    || !nativeIds.remove(pending.path("providerCallId").asText())) return false;
        }
        return nativeIds.isEmpty();
    }

    static ObjectNode correct(AgentStore.Run run, String reason) {
        ObjectNode state = run.state();
        if (!eligible(run) || state.path("referenceCorrections").asInt() >= MAX_CORRECTIONS)
            return null;
        int attempt = state.path("referenceCorrections").asInt() + 1;
        ObjectNode audit =
                AgentJson.object()
                        .put("attempt", attempt)
                        .put("maximum", MAX_CORRECTIONS)
                        .put("reason", reason)
                        .put("writePrepared", false)
                        .put("tokensAlreadyCharged", state.path("tokens").asInt());
        audit.set("rejectedCall", state.path("toolIntent").deepCopy());
        String metricFeedback =
                AgentMetricCorrectionFeedback.build(
                        run, state.path("toolIntent").path("arguments"));
        audit.put("metricFeedback", metricFeedback);
        ArrayNode cancelled = audit.putArray("unexecutedCalls");
        int index = 0;
        for (JsonNode pending : state.path("pendingCalls")) {
            cancelled.add(pending.deepCopy());
            String feedback =
                    index++ == 0
                            ? "EVIDENCE_REFERENCE_INVALID："
                                    + reason
                                    + "。诊断未写入。bundle ID"
                                    + " 和工具名不能替代证据条目；根据系统证据映射纠正引用并重新提出诊断。保持事实、候选原因、缺口区分，禁止为了通过校验编造证据。\n"
                                    + metricFeedback
                            : "CANCELLED_BEFORE_EXECUTION：同批诊断引用校验失败；本工具未执行、未创建审批。需要在纠正诊断后重新提出。";
            ((ArrayNode) state.path("messages"))
                    .add(
                            AgentJson.object()
                                    .put("role", "tool")
                                    .put("tool_call_id", pending.path("providerCallId").asText())
                                    .put("content", feedback));
        }
        state.put("referenceCorrections", attempt);
        state.put("referenceCorrectionPending", true);
        state.remove("toolIntent");
        state.putArray("pendingCalls");
        state.remove("referenceFailure");
        return audit;
    }

    static ObjectNode resume(AgentStore.Run run) {
        if (run.state().path("referenceFailure").path("exhausted").asBoolean())
            throw AgentJson.invalid("引用纠正已用尽 2 次；保留证据交由人工处理，恢复不会重置次数或重放无效诊断");
        if (run.state().path("referenceFailure").path("manualRequired").asBoolean())
            throw AgentJson.invalid("该引用失败不满足自动纠正条件；保留原意图供人工核验，禁止机械重放");
        String message = run.state().path("message").asText();
        if (!message.contains("证据支持结论必须引用本运行后端证据包中的实际证据ID")) return null;
        if (!eligible(run)) throw AgentJson.invalid("该引用失败并非可确认的写入前参数错误；保留原意图，禁止自动重放");
        JsonNode args = run.state().path("toolIntent").path("arguments");
        AgentTools.validateForSnapshot(run, "ticket_add_analysis", args);
        try {
            AgentEvidenceRegistry.validate(run, args);
        } catch (AgentEvidenceRegistry.ReferenceError failure) {
            ObjectNode audit = correct(run, failure.getMessage());
            if (audit == null) throw AgentJson.invalid("引用纠正已用尽，不能机械重放无效诊断");
            return audit.put("legacyResume", true);
        }
        return null;
    }
}
