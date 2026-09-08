package com.opsagent.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 将流程额度记账与模型供应商实际用量分开，缺失的实际向量用量保持未知。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AgentRunUsage {
    private AgentRunUsage() {}

    static ObjectNode budget(ObjectNode state) {
        boolean unlimited = AgentRuntime.unlimited(state);
        long charged = Math.max(0, state.path("tokens").asLong());
        ObjectNode result =
                AgentJson.object()
                        .put("mode", unlimited ? "UNLIMITED" : "LIMITED")
                        .put("chargedTokens", charged)
                        .put("basis", "BUDGET_ACCOUNTING_INCLUDES_RESERVATIONS");
        if (unlimited) {
            result.putNull("limitTokens");
            result.putNull("remainingTokens");
        } else {
            long limit = AgentRuntime.tokenLimit(state);
            result.put("limitTokens", limit).put("remainingTokens", Math.max(0, limit - charged));
        }
        return result;
    }

    static ObjectNode embedding(ObjectNode state) {
        long reserved = 0;
        int count = 0;
        for (var reservation : state.path("toolAiReservations")) {
            if (reservation.isIntegralNumber() && reservation.asLong() > 0) {
                reserved += reservation.asLong();
                count++;
            }
        }
        return AgentJson.object()
                .put("reservedTokens", reserved)
                .put("reservationCount", count)
                .putNull("actualTokens")
                .put("actualUsageKnown", false)
                .put("basis", "QUERY_EMBEDDING_UPPER_BOUND");
    }
}
