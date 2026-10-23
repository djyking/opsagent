package com.opsagent.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.JwtService;
import com.opsagent.common.security.OpsPrincipal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Existing OpsAgent identities delegated to explicitly granted Harness applications. Raw login
 * tokens and application credentials are never stored in delegation records.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
@ConditionalOnProperty(name = "ops.harness.identity.enabled", havingValue = "true")
public class HarnessIdentityService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PERMISSIONS =
            Set.of(
                    "runs:create",
                    "runs:read",
                    "runs:list",
                    "runs:events:read",
                    "runs:control",
                    "runs:output:read",
                    "runs:admin",
                    "catalog:read",
                    "catalog:write",
                    "catalog:validate",
                    "catalog:publish",
                    "approvals:read",
                    "approvals:decide",
                    "approvals:review",
                    "runs:reconcile:read",
                    "runs:reconcile",
                    "operations:admin",
                    "onboarding:request",
                    "onboarding:admin",
                    "onboarding:platform-admin",
                    "studio:knowledge",
                    "opsagent:rag:search",
                    "model:invoke");
    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final JwtService jwt;
    private final InternalActorTokens tokens;

    public HarnessIdentityService(
            JdbcTemplate jdbc,
            AuthService auth,
            JwtService jwt,
            @Value("${ops.agent.internal-secret:${OPS_AGENT_INTERNAL_SECRET:}}") String secret) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.jwt = jwt;
        this.tokens = new InternalActorTokens(secret);
        try {
            jdbc.queryForList(
                    "SELECT application_id,credential_hash,enabled,revision"
                            + " FROM ops_harness_application WHERE 1=0");
            jdbc.queryForList(
                    "SELECT"
                        + " application_id,project_id,user_id,required_role,permissions_json,enabled,revision"
                        + " FROM ops_harness_grant WHERE 1=0");
            jdbc.queryForList(
                    "SELECT id,run_id,roles_json,permissions_json,expires_at,revoked"
                            + " FROM ops_harness_delegation WHERE 1=0");
        } catch (RuntimeException missing) {
            throw new IllegalStateException("HARNESS_IDENTITY_SCHEMA_MIGRATION_REQUIRED");
        }
    }

    public IdentityView introspect(
            String applicationAuthorization, String projectId, String userToken) {
        String application = application(applicationAuthorization);
        OpsPrincipal principal = login(userToken);
        return identity(application, projectId, principal.userId(), principal.roles());
    }

    public DelegationView delegate(
            String applicationAuthorization,
            String projectId,
            String userToken,
            String runId,
            Instant deadline) {
        String application = application(applicationAuthorization);
        OpsPrincipal principal = login(userToken);
        IdentityView identity =
                identity(application, projectId, principal.userId(), principal.roles());
        if (runId == null
                || !runId.matches("[A-Za-z0-9._:-]{1,128}")
                || deadline == null
                || !deadline.isAfter(Instant.now())
                || deadline.isAfter(Instant.now().plusSeconds(86400))) {
            throw denied(ErrorCode.VALIDATION, "HARNESS_DELEGATION_INVALID");
        }
        // Freeze only current roles covered by the authenticated login token.
        List<String> ceiling = currentRoles(principal.userId(), principal.roles());
        String id = UUID.randomUUID().toString();
        long until = deadline.getEpochSecond();
        try {
            jdbc.update(
                    "INSERT INTO ops_harness_delegation"
                            + " (id,application_id,project_id,user_id,run_id,roles_json,"
                            + "permissions_json,expires_at,revoked)"
                            + " VALUES(?,?,?,?,?,?,?,?,0)",
                    id,
                    application,
                    projectId,
                    principal.userId(),
                    runId,
                    json(ceiling),
                    json(identity.permissions()),
                    until);
        } catch (DuplicateKeyException conflict) {
            Delegation existing = byRun(application, runId);
            if (!existing.project().equals(projectId)
                    || existing.user() != principal.userId()
                    || existing.expiresAt() != until) {
                throw denied(ErrorCode.CONFLICT, "HARNESS_DELEGATION_CONFLICT");
            }
            identity = identityFor(existing);
            return new DelegationView(
                    existing.id(),
                    Instant.ofEpochSecond(existing.expiresAt()).toString(),
                    identity);
        }
        return new DelegationView(id, Instant.ofEpochSecond(until).toString(), identity);
    }

    public IdentityView current(
            String applicationAuthorization, String delegationId, String runId) {
        return identityFor(delegation(applicationAuthorization, delegationId, runId));
    }

    public TokenView token(
            String applicationAuthorization, String delegationId, String runId, String audience) {
        if (!"rag".equals(audience)) throw denied(ErrorCode.FORBIDDEN, "HARNESS_AUDIENCE_DENIED");
        Delegation delegation = delegation(applicationAuthorization, delegationId, runId);
        IdentityView identity = identityFor(delegation);
        if (!identity.permissions().contains("opsagent:rag:search")) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_RETRIEVAL_DENIED");
        }
        AuthService.ActorView actor = auth.actor(delegation.user());
        List<String> roles = currentRoles(delegation.user(), strings(delegation.roles()));
        Instant deadline = Instant.ofEpochSecond(delegation.expiresAt());
        if (actor.expiresAt() != null && actor.expiresAt().isBefore(deadline))
            deadline = actor.expiresAt();
        String authorization =
                "Bearer "
                        + tokens.issue(
                                "rag",
                                new InternalActorTokens.Context(
                                        actor.userId(),
                                        actor.username(),
                                        roles,
                                        runId,
                                        "",
                                        deadline));
        Instant expiry =
                deadline.isBefore(Instant.now().plusSeconds(120))
                        ? deadline
                        : Instant.now().plusSeconds(120);
        return new TokenView(authorization, expiry.toString());
    }

    /** Historical output reads do not renew the original execution lease or its authority. */
    public TokenView outputToken(
            String applicationAuthorization,
            String delegationId,
            String projectId,
            String subject,
            String runId) {
        Delegation delegation = delegation(applicationAuthorization, delegationId, runId);
        if (!delegation.project().equals(projectId)
                || !Long.toString(delegation.user()).equals(subject)) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_OUTPUT_SCOPE_DENIED");
        }
        IdentityView identity = identityWithinCeiling(delegation);
        if (!identity.permissions()
                .containsAll(List.of("runs:output:read", "opsagent:rag:search"))) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_OUTPUT_DENIED");
        }
        AuthService.ActorView actor = auth.actor(delegation.user());
        Instant now = Instant.now();
        if (!actor.active()
                || actor.revokedAt() != null
                || actor.userId() != delegation.user()
                || actor.userId() < 0 && actor.expiresAt() == null
                || actor.expiresAt() != null && !actor.expiresAt().isAfter(now)) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_ACTOR_DISABLED");
        }
        List<String> roles = currentRoles(delegation.user(), strings(delegation.roles()));
        Instant deadline = now.plusSeconds(120);
        if (actor.expiresAt() != null && actor.expiresAt().isBefore(deadline))
            deadline = actor.expiresAt();
        String authorization =
                "Bearer "
                        + tokens.issue(
                                "rag-output",
                                new InternalActorTokens.Context(
                                        actor.userId(),
                                        actor.username(),
                                        roles,
                                        runId,
                                        "",
                                        deadline));
        return new TokenView(authorization, deadline.toString());
    }

    private IdentityView identityFor(Delegation delegation) {
        if (delegation.revoked() || delegation.expiresAt() <= Instant.now().getEpochSecond()) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_DELEGATION_EXPIRED_OR_REVOKED");
        }
        return identityWithinCeiling(delegation);
    }

    private IdentityView identityWithinCeiling(Delegation delegation) {
        if (delegation.revoked()) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_DELEGATION_EXPIRED_OR_REVOKED");
        }
        IdentityView current =
                identity(
                        delegation.application(),
                        delegation.project(),
                        delegation.user(),
                        strings(delegation.roles()));
        List<String> ceiling = strings(delegation.permissions());
        return new IdentityView(
                current.applicationId(),
                current.projectId(),
                current.subject(),
                current.permissions().stream().filter(ceiling::contains).toList());
    }

    private Delegation delegation(String authorization, String id, String runId) {
        String app = application(authorization);
        if (id == null || !id.matches("[a-f0-9-]{36}") || runId == null) {
            throw denied(ErrorCode.NOT_FOUND, "HARNESS_DELEGATION_NOT_FOUND");
        }
        List<Delegation> rows =
                jdbc.query(
                        "SELECT * FROM ops_harness_delegation"
                                + " WHERE id=? AND application_id=? AND run_id=?",
                        HarnessIdentityService::row,
                        id,
                        app,
                        runId);
        if (rows.size() != 1) throw denied(ErrorCode.NOT_FOUND, "HARNESS_DELEGATION_NOT_FOUND");
        return rows.get(0);
    }

    private Delegation byRun(String app, String runId) {
        List<Delegation> rows =
                jdbc.query(
                        "SELECT * FROM ops_harness_delegation"
                                + " WHERE application_id=? AND run_id=?",
                        HarnessIdentityService::row,
                        app,
                        runId);
        if (rows.size() != 1) throw denied(ErrorCode.CONFLICT, "HARNESS_DELEGATION_CONFLICT");
        return rows.get(0);
    }

    private static Delegation row(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new Delegation(
                rs.getString("id"),
                rs.getString("application_id"),
                rs.getString("project_id"),
                rs.getLong("user_id"),
                rs.getString("run_id"),
                rs.getString("roles_json"),
                rs.getString("permissions_json"),
                rs.getLong("expires_at"),
                rs.getBoolean("revoked"));
    }

    private String application(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9._~+/=-]{32,512}")) {
            throw denied(ErrorCode.UNAUTHENTICATED, "HARNESS_APPLICATION_REQUIRED");
        }
        List<String> apps =
                jdbc.query(
                        "SELECT application_id FROM ops_harness_application"
                                + " WHERE credential_hash=? AND enabled=1",
                        (rs, row) -> rs.getString(1),
                        sha256(authorization.substring(7)));
        if (apps.size() != 1)
            throw denied(ErrorCode.UNAUTHENTICATED, "HARNESS_APPLICATION_INVALID");
        return apps.get(0);
    }

    private OpsPrincipal login(String token) {
        if (token == null || token.length() > 10000) {
            throw denied(ErrorCode.UNAUTHENTICATED, "HARNESS_USER_TOKEN_INVALID");
        }
        try {
            return jwt.parse(token);
        } catch (RuntimeException invalid) {
            throw denied(ErrorCode.UNAUTHENTICATED, "HARNESS_USER_TOKEN_INVALID");
        }
    }

    private IdentityView identity(String app, String project, long user, List<String> ceiling) {
        if (project == null || !project.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_PROJECT_DENIED");
        }
        List<String> roles = currentRoles(user, ceiling);
        List<Grant> grants =
                jdbc.query(
                        "SELECT required_role,permissions_json FROM ops_harness_grant WHERE"
                                + " application_id=? AND project_id=? AND user_id=? AND enabled=1",
                        (rs, row) -> new Grant(rs.getString(1), rs.getString(2)),
                        app,
                        project,
                        user);
        if (grants.size() != 1 || !roles.contains(normalizeRole(grants.get(0).role()))) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_GRANT_DENIED");
        }
        List<String> permissions = strings(grants.get(0).permissions());
        if (permissions.isEmpty()
                || permissions.stream()
                        .anyMatch(
                                permission ->
                                        !PERMISSIONS.contains(permission)
                                                && !permission.matches(
                                                        "tool:[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}"))) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_GRANT_INVALID");
        }
        return new IdentityView(app, project, Long.toString(user), permissions);
    }

    private List<String> currentRoles(long userId, List<String> ceiling) {
        AuthService.ActorView current = auth.actor(userId);
        if (!current.active() || current.revokedAt() != null) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_ACTOR_DISABLED");
        }
        var normalizedCeiling =
                ceiling.stream().map(HarnessIdentityService::normalizeRole).toList();
        List<String> roles =
                current.roles().stream()
                        .map(HarnessIdentityService::normalizeRole)
                        .filter(normalizedCeiling::contains)
                        .distinct()
                        .sorted()
                        .toList();
        if (roles.isEmpty()) throw denied(ErrorCode.FORBIDDEN, "HARNESS_ROLES_REVOKED");
        return roles;
    }

    private static String normalizeRole(String role) {
        return role.startsWith("ROLE_") ? role.substring(5) : role;
    }

    private static List<String> strings(String json) {
        try {
            return List.copyOf(JSON.readValue(json, new TypeReference<List<String>>() {}));
        } catch (Exception failure) {
            throw denied(ErrorCode.FORBIDDEN, "HARNESS_GRANT_INVALID");
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("HARNESS_IDENTITY_INVALID");
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    private static BusinessException denied(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }

    /**
     * @author heyu
     */
    public record IdentityView(
            String applicationId, String projectId, String subject, List<String> permissions) {}

    /**
     * @author heyu
     */
    public record DelegationView(String delegationId, String expiresAt, IdentityView identity) {}

    /**
     * @author heyu
     */
    public record TokenView(String authorization, String expiresAt) {}

    /**
     * @author heyu
     */
    private record Grant(String role, String permissions) {}

    /**
     * @author heyu
     */
    private record Delegation(
            String id,
            String application,
            String project,
            long user,
            String run,
            String roles,
            String permissions,
            long expiresAt,
            boolean revoked) {}
}
