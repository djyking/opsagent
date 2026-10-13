package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.InternalActorTokens;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 真实数据库验证已知用量、未知尝试、历史缺口和读取范围。
 *
 * @author heyu
 * @since 2026/9/3
 */
class InternalAgentUsageServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private InternalAgentStore store;
    private InternalAgentUsageService service;

    @BeforeEach
    void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:usage-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        store = new InternalAgentStore(jdbc, mapper);
        store.initialize();
        jdbc.execute(
                "CREATE TABLE ai_usage_log(question_hash VARCHAR(64),provider"
                    + " VARCHAR(20),input_tokens INT,output_tokens INT,error_code VARCHAR(80))");
        service = new InternalAgentUsageService(jdbc, mapper);
    }

    @Test
    void aggregatesActualReceiptsSeparatelyFromUnknownReservationsAndReplay() {
        turn("one", 7, "SUCCEEDED", null);
        store.beginAttempt("one", 1, "DEEPSEEK", 19000);
        store.finishAttempt("one", 1, null, "MODEL_NETWORK_ERROR");
        store.beginAttempt("one", 2, "DEEPSEEK", 19000);
        store.finishAttempt("one", 2, response(120, 30), null);
        var result = service.usage("run-1", actor(7, "USER"));
        assertThat(result.path("knownInputTokens").asInt()).isEqualTo(120);
        assertThat(result.path("knownOutputTokens").asInt()).isEqualTo(30);
        assertThat(result.path("knownTotalTokens").asInt()).isEqualTo(150);
        assertThat(result.path("knownUsageAttempts").asInt()).isEqualTo(1);
        assertThat(result.path("unknownUsageAttempts").asInt()).isEqualTo(1);
        assertThat(result.path("unknownCountIsLowerBound").asBoolean()).isFalse();
        assertThat(result.toString()).doesNotContain("19000");
        assertThat(service.usage("run-1", actor(7, "USER"))).isEqualTo(result);
    }

    @Test
    void historicalRetryReceiptPreservesKnownLastAttemptDespiteOverallUnknownUsage()
            throws Exception {
        var receipt = mapper.valueToTree(response(200, 50)).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) receipt)
                .put("usageKnown", false)
                .put("attempts", 2)
                .put("budgetTokens", 22000);
        turn("old", 7, "SUCCEEDED", receipt.toString());
        var result = service.usage("run-1", actor(7, "USER"));
        assertThat(result.path("knownTotalTokens").asInt()).isEqualTo(250);
        assertThat(result.path("unknownUsageAttempts").asInt()).isEqualTo(1);
        assertThat(result.path("coverage").asText()).isEqualTo("COMPLETE");
    }

    @Test
    void missingHistoricalAuditIsUnknownLowerBoundAndAdmissionFailureIsNotAModelAttempt() {
        turn("lost", 7, "UNKNOWN", null);
        turn("rejected", 7, "FAILED", null);
        var result = service.usage("run-1", actor(7, "USER"));
        assertThat(result.path("knownTotalTokens").asInt()).isZero();
        assertThat(result.path("unknownUsageAttempts").asInt()).isEqualTo(1);
        assertThat(result.path("unknownCountIsLowerBound").asBoolean()).isTrue();
        assertThat(result.path("coverage").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void filtersOtherOwnersAndRequiresSignedRunScope() {
        turn("private", 8, "SUCCEEDED", store.json(response(8, 2)));
        assertThat(service.usage("run-1", actor(7, "USER")).path("knownTotalTokens").asInt())
                .isZero();
        assertThat(service.usage("run-1", actor(7, "ADMIN")).path("knownTotalTokens").asInt())
                .isEqualTo(10);
        assertThatThrownBy(() -> service.usage("other-run", actor(7, "ADMIN")))
                .hasMessage("RUN_SCOPE_MISMATCH");
    }

    @Test
    void incompleteReceiptAndPendingAttemptNeverInventActualTokens() {
        turn("pending", 7, "STARTED", null);
        store.beginAttempt("pending", 1, "DEEPSEEK", 23000);
        var result = service.usage("run-1", actor(7, "USER"));
        assertThat(result.path("knownTotalTokens").asInt()).isZero();
        assertThat(result.path("pendingCalls").asInt()).isEqualTo(1);
        assertThat(result.path("unknownUsageAttempts").asInt()).isEqualTo(1);
        assertThat(result.path("unknownCountIsLowerBound").asBoolean()).isTrue();
    }

    private void turn(String id, long owner, String status, String json) {
        jdbc.update(
                "INSERT INTO"
                    + " rag_agent_model_turn(call_id,actor_id,run_id,request_hash,status,result_json)"
                    + " VALUES(?,?,'run-1','hash',?,?)",
                id,
                owner,
                status,
                json);
    }

    private InternalAgentDtos.TurnResponse response(int input, int output) {
        return new InternalAgentDtos.TurnResponse(
                "FINAL",
                Map.of("role", "assistant", "content", "ok"),
                input,
                output,
                input + output,
                true,
                "DEEPSEEK",
                "model",
                "stop",
                10);
    }

    private InternalActorTokens.Context actor(long owner, String role) {
        return new InternalActorTokens.Context(
                owner, "reader", List.of(role), "run-1", "", Instant.now().plusSeconds(60));
    }
}
