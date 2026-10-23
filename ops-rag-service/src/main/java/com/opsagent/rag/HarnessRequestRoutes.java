package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Durable single execution ownership for the read-only Harness search pilot. Switching the
 * configured owner changes new requests only; failures never change an existing owner.
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class HarnessRequestRoutes {
    private final JdbcTemplate jdbc;

    public HarnessRequestRoutes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Route assign(
            String application,
            String project,
            String subject,
            String requestId,
            String inputDigest,
            String newOwner) {
        return assign(application, project, subject, requestId, inputDigest, newOwner, "{}");
    }

    public Route assign(
            String application,
            String project,
            String subject,
            String requestId,
            String inputDigest,
            String newOwner,
            String dispatchJson) {
        if (!java.util.Set.of("LEGACY", "HARNESS").contains(newOwner)) {
            throw new IllegalArgumentException("HARNESS_ROUTE_OWNER_INVALID");
        }
        String key = hash(parts(application, project, subject, requestId));
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update(
                    "INSERT INTO ops_harness_route"
                        + " (request_key,id,input_digest,execution_owner,expires_at,dispatch_json)"
                        + " VALUES(?,?,?,?,?,?)",
                    key,
                    id,
                    inputDigest,
                    newOwner,
                    java.time.Instant.now().plusSeconds(3600).getEpochSecond(),
                    dispatchJson);
        } catch (DuplicateKeyException duplicate) {
            // The unique request key is the arbitration point across threads, processes and
            // restarts.
        }
        Route existing =
                jdbc.queryForObject(
                        "SELECT id,input_digest,execution_owner,dispatch_json FROM"
                                + " ops_harness_route WHERE request_key=?",
                        (rs, row) ->
                                new Route(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4)),
                        key);
        if (existing == null || !existing.inputDigest().equals(inputDigest)) {
            throw new BusinessException(ErrorCode.CONFLICT, "HARNESS_REQUEST_INPUT_CONFLICT");
        }
        return existing;
    }

    public java.time.Instant deadline(String routeId) {
        Long value =
                jdbc.queryForObject(
                        "SELECT expires_at FROM ops_harness_route WHERE id=?", Long.class, routeId);
        if (value == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "HARNESS_ROUTE_NOT_FOUND");
        return java.time.Instant.ofEpochSecond(value);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    private static String parts(String... values) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 128) {
                throw new IllegalArgumentException("HARNESS_ROUTE_IDENTITY_INVALID");
            }
            text.append(value.length()).append(':').append(value);
        }
        return text.toString();
    }

    /**
     * @author heyu
     */
    public record Route(String id, String inputDigest, String owner, String dispatchJson) {}
}
