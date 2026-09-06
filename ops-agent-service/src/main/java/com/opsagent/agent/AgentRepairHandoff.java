package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens.Context;

import java.time.Instant;

/**
 * 已审批的真实修复只交接到冻结内置图的验证节点，不代表业务或工单已经恢复。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AgentRepairHandoff {
    private AgentRepairHandoff() {}

    static void record(
            AgentStore.Run run, JsonNode call, JsonNode result, JsonNode approval, Context actor) {
        ObjectNode state = run.state();
        String name = call.path("name").asText();
        String target = state.path("targetCode").asText(AgentTargets.ORDER);
        String revision = result.path("expectedRevision").asText();
        if (!AgentTools.HIGH.contains(name)
                || approval == null
                || !approval.path("status").asText().equals("APPROVED")
                || !approval.path("args_hash").asText().equals(AgentJson.hash(call))
                || !run.node().equals(state.path("diagnosisNode").asText())
                || state.path("diagnosis").path("summary").asText().isBlank()
                || !target.equals(actor.targetCode())
                || !target.equals(result.path("targetCode").asText())
                || !result.path("scope").asText().equals("ISOLATED_DEMO")
                || !state.path("incidentId").asText().equals(result.path("incidentId").asText())
                || !result.path("actionAccepted").asBoolean()
                || !result.path("status").asText().equals("BASELINE")
                || !result.path("configurationStatus").asText().equals("APPLIED")
                || !result.path("recoverySource").asText().equals("AGENT_TOOL")
                || !revision.matches("[a-f0-9]{64}")
                || !revision.equals(result.path("appliedRevision").asText())
                || revision.equals(call.path("arguments").path("expectedRevision").asText())
                || !validObservation(result.path("observedAt").asText())) {
            return;
        }
        ObjectNode receipt =
                AgentJson.object()
                        .put("node", run.node())
                        .put("callId", call.path("id").asText())
                        .put("tool", name)
                        .put("approvalId", approval.path("id").asText())
                        .put("approvalHash", approval.path("args_hash").asText())
                        .put("incidentId", state.path("incidentId").asText())
                        .put("targetCode", target)
                        .put(
                                "revisionBefore",
                                call.path("arguments").path("expectedRevision").asText())
                        .put("revisionAfter", revision)
                        .put("observedAt", result.path("observedAt").asText())
                        .put(
                                "diagnosisRecordedAt",
                                state.path("diagnosis").path("recordedAt").asText());
        state.set("approvedRepair", receipt);
    }

    static JsonNode output(AgentStore.Run run) {
        ObjectNode state = run.state();
        JsonNode graph = run.snapshot().path("graph");
        JsonNode receipt = state.path("approvedRepair");
        // Exact snapshot equality also protects edited prompts, custom approvals and conditions.
        if (!graph.equals(WorkflowGraph.builtin())
                || !run.node().equals(state.path("diagnosisNode").asText())
                || !run.node().equals(receipt.path("node").asText())
                || !state.path("incidentId").asText().equals(receipt.path("incidentId").asText())
                || !state.path("targetCode")
                        .asText(AgentTargets.ORDER)
                        .equals(receipt.path("targetCode").asText())
                || !state.path("diagnosis")
                        .path("recordedAt")
                        .asText()
                        .equals(receipt.path("diagnosisRecordedAt").asText())
                || receipt.path("approvalId").asText().isBlank()
                || !state.path("observations").has(receipt.path("callId").asText())
                || state.has("modelIntent")
                || state.has("toolIntent")
                || !state.path("pendingCalls").isEmpty()) {
            return null;
        }
        JsonNode next = WorkflowGraph.node(graph, WorkflowGraph.next(graph, run.node(), null));
        if (!next.path("type").asText().equals("TOOL")
                || !next.path("config").path("tool").asText().equals("ticket_resolve")) {
            return null;
        }
        ObjectNode output =
                AgentJson.object()
                        .put("completionSource", "APPROVED_REPAIR_REQUIRES_VERIFICATION")
                        .put("verificationStatus", "PENDING");
        output.set("approvedRepair", receipt.deepCopy());
        return output;
    }

    private static boolean validObservation(String value) {
        try {
            Instant observed = Instant.parse(value);
            // A committed idempotent action may be replayed after a lease recovery. Its timestamp
            // stays original; only the subsequent independent recovery probes must be fresh.
            return !observed.isAfter(Instant.now().plusSeconds(2));
        } catch (RuntimeException invalidTimestamp) {
            return false;
        }
    }
}
