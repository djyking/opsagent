package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读汇总每次供应商调用的用量回执；历史缺口显示为未知下界，不把预算预留当消费。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class InternalAgentUsageService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    InternalAgentUsageService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    ObjectNode usage(String runId, InternalActorTokens.Context actor) {
        if (!runId.equals(actor.runId()))
            throw new BusinessException(ErrorCode.FORBIDDEN, "RUN_SCOPE_MISMATCH");
        var turns =
                jdbc.queryForList(
                        "SELECT call_id,status,result_json FROM rag_agent_model_turn WHERE run_id=?"
                                + (actor.roles().contains("ADMIN") ? "" : " AND actor_id=?"),
                        actor.roles().contains("ADMIN")
                                ? new Object[] {runId}
                                : new Object[] {runId, actor.userId()});
        Map<String, Totals> providers = new LinkedHashMap<>();
        Totals total = new Totals();
        int pending = 0;
        boolean partial = false;
        for (var turn : turns) {
            String callId = String.valueOf(turn.get("call_id"));
            String status = String.valueOf(turn.get("status"));
            if ("STARTED".equals(status)) pending++;
            var attempts =
                    jdbc.queryForList(
                            "SELECT provider,status,input_tokens,output_tokens,total_tokens FROM"
                                + " rag_agent_model_attempt WHERE call_id=? ORDER BY attempt_no",
                            callId);
            if (!attempts.isEmpty()) {
                for (var attempt : attempts) {
                    String provider = String.valueOf(attempt.get("provider"));
                    Totals group = providers.computeIfAbsent(provider, ignored -> new Totals());
                    if ("KNOWN".equals(attempt.get("status"))) {
                        long input = number(attempt.get("input_tokens"));
                        long output = number(attempt.get("output_tokens"));
                        long tokens = number(attempt.get("total_tokens"));
                        if (input >= 0 && output >= 0 && tokens >= input + output) {
                            total.known(input, output, tokens);
                            group.known(input, output, tokens);
                            continue;
                        }
                    }
                    total.unknown++;
                    group.unknown++;
                    if ("STARTED".equals(attempt.get("status"))) partial = true;
                }
                continue;
            }
            // Historical rows are never rewritten. A stored response identifies one known attempt;
            // attempts before that response are explicitly unknown, even when budgetTokens is
            // large.
            JsonNode receipt = read(turn.get("result_json"));
            var audits =
                    jdbc.queryForList(
                            "SELECT provider,input_tokens,output_tokens,error_code"
                                    + " FROM ai_usage_log WHERE question_hash=?",
                            InternalAgentStore.hash(callId));
            String provider =
                    receipt.path("provider")
                            .asText(
                                    audits.isEmpty()
                                            ? "UNKNOWN"
                                            : String.valueOf(audits.get(0).get("provider")));
            Totals group = providers.computeIfAbsent(provider, ignored -> new Totals());
            if ("SUCCEEDED".equals(status) && receipt.isObject()) {
                int count = Math.max(1, receipt.path("attempts").asInt(1));
                boolean known = countsKnown(receipt);
                if (known) {
                    long input = receipt.path("inputTokens").asLong();
                    long output = receipt.path("outputTokens").asLong();
                    long tokens = receipt.path("totalTokens").asLong();
                    total.known(input, output, tokens);
                    group.known(input, output, tokens);
                }
                total.unknown += count - (known ? 1 : 0);
                group.unknown += count - (known ? 1 : 0);
                continue;
            }
            int accounted = 0;
            for (var audit : audits) {
                String error = String.valueOf(audit.get("error_code"));
                if ("MODEL_BUDGET_REJECTED".equals(error)) continue;
                long input = number(audit.get("input_tokens"));
                long output = number(audit.get("output_tokens"));
                if (audit.get("error_code") == null && input >= 0 && output >= 0) {
                    total.known(input, output, input + output);
                    group.known(input, output, input + output);
                } else {
                    total.unknown++;
                    group.unknown++;
                }
                accounted++;
            }
            if (!"FAILED".equals(status)) {
                if (accounted == 0) {
                    total.unknown++;
                    group.unknown++;
                }
                // Existing audit writes were best effort; failure rows cannot prove the exact
                // count.
                partial = true;
            }
        }
        ObjectNode result =
                total.json(mapper)
                        .put("availability", "AVAILABLE")
                        .put("pendingCalls", pending)
                        .put("unknownCountIsLowerBound", partial)
                        .put("coverage", partial ? "PARTIAL" : "COMPLETE");
        var entries = result.putArray("providers");
        providers.forEach(
                (provider, totals) -> entries.add(totals.json(mapper).put("provider", provider)));
        return result;
    }

    private boolean countsKnown(JsonNode value) {
        for (String field : List.of("inputTokens", "outputTokens", "totalTokens")) {
            if (!value.path(field).isIntegralNumber() || value.path(field).asLong(-1) < 0)
                return false;
        }
        return value.path("totalTokens").asLong()
                >= value.path("inputTokens").asLong() + value.path("outputTokens").asLong();
    }

    private JsonNode read(Object json) {
        try {
            return json == null ? mapper.missingNode() : mapper.readTree(String.valueOf(json));
        } catch (Exception invalid) {
            return mapper.missingNode();
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1;
    }

    /**
     * @author heyu
     */
    private static final class Totals {
        long input;
        long output;
        long tokens;
        int known;
        int unknown;

        void known(long input, long output, long tokens) {
            this.input += input;
            this.output += output;
            this.tokens += tokens;
            known++;
        }

        ObjectNode json(ObjectMapper mapper) {
            return mapper.createObjectNode()
                    .put("knownInputTokens", input)
                    .put("knownOutputTokens", output)
                    .put("knownTotalTokens", tokens)
                    .put("knownUsageAttempts", known)
                    .put("unknownUsageAttempts", unknown);
        }
    }
}
