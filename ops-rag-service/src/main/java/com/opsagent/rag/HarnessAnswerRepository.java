package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Durable execution ownership. A lost result never changes owner or replays a legacy generation.
 *
 * @author heyu
 * @since 2026/9/3
 */
final class HarnessAnswerRepository {
    private final JdbcTemplate jdbc;

    HarnessAnswerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        try {
            jdbc.queryForList(
                    """
                    SELECT id,request_key,input_digest,execution_owner,state,
                      request_json,context_json,prompt_sha256,dispatch_json,platform_run_id,
                      answer_json,created_epoch,completed_epoch,platform_status
                    FROM ops_harness_answer WHERE 1=0
                    """);
        } catch (RuntimeException missing) {
            throw new IllegalStateException("HARNESS_ANSWER_SCHEMA_MIGRATION_REQUIRED");
        }
    }

    Row assign(String app, String project, long user, String key, String request, String owner) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"))
            throw failure(ErrorCode.VALIDATION, "HARNESS_IDEMPOTENCY_KEY_REQUIRED");
        if (!Set.of("LEGACY", "HARNESS").contains(owner))
            throw new IllegalArgumentException("HARNESS_OWNER_INVALID");
        String digest = HarnessRequestRoutes.hash(request);
        String requestKey =
                HarnessRequestRoutes.hash(app + "\n" + project + "\n" + user + "\n" + key);
        try {
            jdbc.update(
                    """
                    INSERT INTO ops_harness_answer
                    (id,request_key,input_digest,application_id,project_id,user_id,
                     execution_owner,state,request_json,created_epoch)
                    VALUES(?,?,?,?,?,?,?,'NEW',?,?)
                    """,
                    UUID.randomUUID().toString(),
                    requestKey,
                    digest,
                    app,
                    project,
                    user,
                    owner,
                    request,
                    Instant.now().getEpochSecond());
        } catch (DuplicateKeyException race) {
            // The unique key, not a process-local lock, arbitrates across service instances.
        }
        Row row = one("request_key", requestKey);
        if (!row.inputDigest().equals(digest))
            throw failure(ErrorCode.CONFLICT, "HARNESS_REQUEST_INPUT_CONFLICT");
        return row;
    }

    Row get(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}"))
            throw failure(ErrorCode.NOT_FOUND, "HARNESS_ANSWER_NOT_FOUND");
        return one("id", id);
    }

    private Row one(String column, String value) {
        var rows =
                jdbc.query(
                        "SELECT * FROM ops_harness_answer WHERE " + column + "=?",
                        (rs, i) ->
                                new Row(
                                        rs.getString("id"),
                                        rs.getString("input_digest"),
                                        rs.getString("application_id"),
                                        rs.getString("project_id"),
                                        rs.getLong("user_id"),
                                        rs.getString("execution_owner"),
                                        rs.getString("state"),
                                        rs.getString("request_json"),
                                        rs.getString("context_json"),
                                        rs.getString("prompt_sha256"),
                                        rs.getString("dispatch_json"),
                                        rs.getString("platform_run_id"),
                                        rs.getString("answer_json"),
                                        rs.getLong("created_epoch"),
                                        (Long) rs.getObject("completed_epoch")),
                        value);
        if (rows.size() != 1) throw failure(ErrorCode.NOT_FOUND, "HARNESS_ANSWER_NOT_FOUND");
        return rows.get(0);
    }

    boolean claim(Row row) {
        return jdbc.update(
                        "UPDATE ops_harness_answer SET state='PREPARING' WHERE id=? AND"
                                + " state='NEW'",
                        row.id())
                == 1;
    }

    void prepared(String id, String context, String promptHash, String dispatch) {
        if (jdbc.update(
                        """
                        UPDATE ops_harness_answer SET state='READY',context_json=?,prompt_sha256=?,dispatch_json=?
                        WHERE id=? AND state='PREPARING'
                        """,
                        context,
                        promptHash,
                        dispatch,
                        id)
                != 1) throw failure(ErrorCode.CONFLICT, "HARNESS_PREPARATION_CONFLICT");
    }

    void immediate(String id, String context, String answer) {
        if (jdbc.update(
                        """
                        UPDATE ops_harness_answer SET state='COMPLETED',context_json=?,answer_json=?,completed_epoch=?
                        WHERE id=? AND state='PREPARING'
                        """,
                        context,
                        answer,
                        Instant.now().getEpochSecond(),
                        id)
                != 1) throw failure(ErrorCode.CONFLICT, "HARNESS_PREPARATION_CONFLICT");
    }

    void unknown(String id) {
        jdbc.update(
                "UPDATE ops_harness_answer SET state='UNKNOWN' WHERE id=? AND state='PREPARING'",
                id);
    }

    void rejected(String id) {
        jdbc.update(
                "UPDATE ops_harness_answer SET state='REJECTED',completed_epoch=?"
                        + " WHERE id=? AND state='PREPARING'",
                Instant.now().getEpochSecond(),
                id);
    }

    void observedTerminal(String id, String status) {
        if (!Set.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED", "BUDGET_EXCEEDED")
                .contains(status)) return;
        jdbc.update(
                """
                UPDATE ops_harness_answer SET platform_status=?,completed_epoch=COALESCE(completed_epoch,?)
                WHERE id=? AND platform_run_id IS NOT NULL AND state='SUBMITTED'
                """,
                status,
                Instant.now().getEpochSecond(),
                id);
    }

    void bind(String id, String runId) {
        if (runId == null || !runId.matches("[a-f0-9-]{36}"))
            throw failure(ErrorCode.MIDDLEWARE_UNAVAILABLE, "HARNESS_RUN_RESPONSE_INVALID");
        jdbc.update(
                """
                UPDATE ops_harness_answer SET state='SUBMITTED',platform_run_id=?
                WHERE id=? AND state='READY' AND platform_run_id IS NULL
                """,
                runId,
                id);
        if (!runId.equals(get(id).runId()))
            throw failure(ErrorCode.CONFLICT, "HARNESS_RUN_BINDING_CONFLICT");
    }

    private static BusinessException failure(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }

    /**
     * @author heyu
     */
    record Row(
            String id,
            String inputDigest,
            String application,
            String project,
            long user,
            String owner,
            String state,
            String request,
            String context,
            String promptHash,
            String dispatch,
            String runId,
            String answer,
            long createdEpoch,
            Long completedEpoch) {}
}
