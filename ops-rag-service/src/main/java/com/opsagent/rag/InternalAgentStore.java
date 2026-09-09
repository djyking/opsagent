package com.opsagent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import jakarta.annotation.PostConstruct;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 保存模型能力验证和单次调用回执；进程中断后的未知请求不会自动再次计费。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
class InternalAgentStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    InternalAgentStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS rag_agent_model_capability (provider VARCHAR(20) NOT"
                        + " NULL, model VARCHAR(128) NOT NULL, profile_hash VARCHAR(64) NOT NULL,"
                        + " status VARCHAR(24) NOT NULL, verified_at VARCHAR(40) NOT NULL, PRIMARY"
                        + " KEY(provider,model,profile_hash))");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS rag_agent_model_turn (call_id VARCHAR(160) PRIMARY KEY,"
                    + " actor_id BIGINT NOT NULL, run_id VARCHAR(128) NOT NULL, request_hash"
                    + " VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL, result_json LONGTEXT,"
                    + " error_code VARCHAR(80), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                    + " updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS rag_agent_model_attempt (call_id VARCHAR(160) NOT NULL,"
                    + " attempt_no INT NOT NULL, provider VARCHAR(20) NOT NULL, status VARCHAR(24)"
                    + " NOT NULL, input_tokens BIGINT, output_tokens BIGINT, total_tokens BIGINT,"
                    + " reserved_tokens INT NOT NULL, error_code VARCHAR(80), PRIMARY"
                    + " KEY(call_id,attempt_no))");
    }

    InternalAgentDtos.ModelCapability capability(
            String provider, AiProperties.ProviderSettings settings, boolean configured) {
        var rows =
                jdbc.queryForList(
                        "SELECT status,verified_at FROM rag_agent_model_capability"
                                + " WHERE provider=? AND model=? AND profile_hash=?",
                        provider,
                        settings.getModel(),
                        profile(settings));
        String status = rows.isEmpty() ? "UNKNOWN" : String.valueOf(rows.get(0).get("status"));
        String verifiedAt = rows.isEmpty() ? null : String.valueOf(rows.get(0).get("verified_at"));
        return new InternalAgentDtos.ModelCapability(
                provider,
                settings.getModel(),
                configured,
                configured && "VERIFIED".equals(status),
                status,
                verifiedAt,
                settings.getApiKey() == null || settings.getApiKey().isBlank()
                        ? "MISSING_API_KEY"
                        : settings.getModel() == null || settings.getModel().isBlank()
                                ? "MISSING_MODEL"
                                : !settings.selectable()
                                        ? "INVALID_ENDPOINT"
                                        : configured ? "CONFIGURED" : "DISABLED");
    }

    void capability(String provider, AiProperties.ProviderSettings settings, String status) {
        jdbc.update(
                "INSERT INTO"
                    + " rag_agent_model_capability(provider,model,profile_hash,status,verified_at)"
                    + " VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                    + " status=VALUES(status),verified_at=VALUES(verified_at)",
                provider,
                settings.getModel(),
                profile(settings),
                status,
                Instant.now().toString());
    }

    InternalAgentDtos.TurnResponse claim(
            InternalAgentDtos.TurnRequest request, InternalActorTokens.Context actor) {
        String requestHash = hash(json(request));
        try {
            jdbc.update(
                    "INSERT INTO rag_agent_model_turn(call_id,actor_id,run_id,request_hash,status)"
                            + " VALUES(?,?,?,?,'STARTED')",
                    request.callId(),
                    actor.userId(),
                    actor.runId(),
                    requestHash);
            return null;
        } catch (DuplicateKeyException duplicate) {
            Map<String, Object> row =
                    jdbc.queryForMap(
                            "SELECT actor_id,run_id,request_hash,status,result_json,error_code"
                                    + " FROM rag_agent_model_turn WHERE call_id=?",
                            request.callId());
            if (((Number) row.get("actor_id")).longValue() != actor.userId()
                    || !actor.runId().equals(row.get("run_id"))
                    || !requestHash.equals(row.get("request_hash"))) {
                throw new BusinessException(ErrorCode.CONFLICT, "MODEL_CALL_ID_CONFLICT");
            }
            if ("SUCCEEDED".equals(row.get("status"))) {
                try {
                    return mapper.readValue(
                            String.valueOf(row.get("result_json")),
                            InternalAgentDtos.TurnResponse.class);
                } catch (JsonProcessingException exception) {
                    throw new BusinessException(ErrorCode.CONFLICT, "MODEL_OUTCOME_UNKNOWN");
                }
            }
            String error =
                    List.of("FAILED", "UNKNOWN").contains(row.get("status"))
                                    && row.get("error_code") != null
                            ? String.valueOf(row.get("error_code"))
                            : "MODEL_OUTCOME_UNKNOWN";
            throw new BusinessException(ErrorCode.CONFLICT, error);
        }
    }

    void complete(String callId, InternalAgentDtos.TurnResponse response) {
        int updated =
                jdbc.update(
                        "UPDATE rag_agent_model_turn SET status='SUCCEEDED',result_json=?,"
                            + " updated_at=CURRENT_TIMESTAMP WHERE call_id=? AND status='STARTED'",
                        json(response),
                        callId);
        if (updated != 1) throw new BusinessException(ErrorCode.CONFLICT, "MODEL_OUTCOME_UNKNOWN");
    }

    void failed(String callId, boolean submitted, String code) {
        jdbc.update(
                "UPDATE rag_agent_model_turn SET status=?,error_code=?,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE call_id=? AND status='STARTED'",
                submitted ? "UNKNOWN" : "FAILED",
                code,
                callId);
    }

    void beginAttempt(String callId, int attempt, String provider, int reservation) {
        jdbc.update(
                "INSERT INTO"
                    + " rag_agent_model_attempt(call_id,attempt_no,provider,status,reserved_tokens)"
                    + " VALUES(?,?,?,'STARTED',?)",
                callId,
                attempt,
                provider,
                reservation);
    }

    void finishAttempt(
            String callId, int attempt, InternalAgentDtos.TurnResponse response, String error) {
        boolean known = response != null && response.usageKnown();
        jdbc.update(
                "UPDATE rag_agent_model_attempt SET"
                    + " status=?,input_tokens=?,output_tokens=?,total_tokens=?,error_code=? WHERE"
                    + " call_id=? AND attempt_no=? AND status='STARTED'",
                known ? "KNOWN" : "UNKNOWN",
                known ? response.inputTokens() : null,
                known ? response.outputTokens() : null,
                known ? response.totalTokens() : null,
                error,
                callId,
                attempt);
    }

    String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "INVALID_MODEL_PAYLOAD");
        }
    }

    private String profile(AiProperties.ProviderSettings settings) {
        return hash(
                json(
                        List.of(
                                settings.getBaseUrl(),
                                settings.getApiStyle(),
                                settings.getModel(),
                                "native-tools-v1")));
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
