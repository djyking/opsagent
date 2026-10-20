package com.opsagent.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.JwtProperties;
import com.opsagent.common.security.JwtService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Durable delegation binds trusted application, project, user and run with current revocation
 * checks.
 *
 * @author heyu
 * @since 2026/9/3
 */
class HarnessIdentityServiceTest {
    private static final String APP = "synthetic-application-secret-at-least-thirty-two-characters";
    private static final String INTERNAL =
            "synthetic-internal-signing-key-at-least-thirty-two-characters";
    private JdbcTemplate jdbc;
    private AuthService auth;
    private JwtService jwt;
    private HarnessIdentityService service;
    private String login;

    @BeforeEach
    void setUp() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:identity-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("harness-identity-schema.sql"))
                .execute(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO ops_harness_application(application_id,credential_hash,enabled)"
                        + " VALUES(?,?,1)",
                "pilot-app",
                HarnessIdentityService.sha256(APP));
        jdbc.update(
                "INSERT INTO"
                    + " ops_harness_grant(application_id,project_id,user_id,required_role,permissions_json,enabled)"
                    + " VALUES(?,?,?,'USER',?,1)",
                "pilot-app",
                "ops-dev",
                10,
                "[\"runs:create\",\"runs:read\",\"opsagent:rag:search\"]");
        auth = mock(AuthService.class);
        active(List.of("USER"));
        JwtProperties properties = new JwtProperties();
        properties.setSecret("synthetic-existing-login-signing-secret-at-least-thirty-two");
        jwt = new JwtService(properties);
        login = jwt.issue(10, "synthetic-user", List.of("USER")).token();
        service = new HarnessIdentityService(jdbc, auth, jwt, INTERNAL);
    }

    @Test
    void existingLoginAndExplicitDatabaseGrantRequired() {
        var identity = service.introspect("Bearer " + APP, "ops-dev", login);
        assertThat(identity.subject()).isEqualTo("10");
        assertThat(identity.applicationId()).isEqualTo("pilot-app");
        assertThatThrownBy(() -> service.introspect("Bearer wrong", "ops-dev", login))
                .hasMessageContaining("APPLICATION");
        assertThatThrownBy(() -> service.introspect("Bearer " + APP, "other", login))
                .hasMessageContaining("GRANT_DENIED");
        assertThatThrownBy(() -> service.introspect("Bearer " + APP, "ops-dev", "forged"))
                .hasMessageContaining("USER_TOKEN_INVALID");
    }

    @Test
    void delegationSurvivesObjectReconstructionWithoutPersistedCredential() {
        Instant deadline = Instant.now().plusSeconds(600);
        var first = service.delegate("Bearer " + APP, "ops-dev", login, "run-one", deadline);
        service = new HarnessIdentityService(jdbc, auth, jwt, INTERNAL);
        var replay = service.delegate("Bearer " + APP, "ops-dev", login, "run-one", deadline);
        assertThat(replay.delegationId()).isEqualTo(first.delegationId());
        String stored = jdbc.queryForList("SELECT * FROM ops_harness_delegation").toString();
        assertThat(stored).doesNotContain(login, APP, INTERNAL);
        var minted = service.token("Bearer " + APP, first.delegationId(), "run-one", "rag");
        var actor = new InternalActorTokens(INTERNAL).verify(minted.authorization(), "rag");
        assertThat(actor.userId()).isEqualTo(10);
        assertThat(actor.runId()).isEqualTo("run-one");
        assertThat(actor.roles()).containsExactly("USER");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ops_harness_delegation", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void catalogPermissionsRequireAnExplicitGrantAndUnknownPermissionsStillFailClosed() {
        assertThat(service.introspect("Bearer " + APP, "ops-dev", login).permissions())
                .doesNotContain(
                        "catalog:read", "catalog:write", "catalog:validate", "catalog:publish");
        jdbc.update(
                "UPDATE ops_harness_grant SET permissions_json=?",
                "[\"runs:admin\",\"catalog:read\",\"catalog:write\",\"catalog:validate\",\"catalog:publish\"]");
        assertThat(service.introspect("Bearer " + APP, "ops-dev", login).permissions())
                .containsExactly(
                        "runs:admin",
                        "catalog:read",
                        "catalog:write",
                        "catalog:validate",
                        "catalog:publish");
        assertThatThrownBy(() -> service.introspect("Bearer " + APP, "other", login))
                .hasMessageContaining("GRANT_DENIED");
        jdbc.update(
                "UPDATE ops_harness_grant SET permissions_json=?",
                "[\"catalog:read\",\"catalog:root\"]");
        assertThatThrownBy(() -> service.introspect("Bearer " + APP, "ops-dev", login))
                .hasMessageContaining("GRANT_INVALID");
    }

    @Test
    void catalogDelegationCannotGainLaterPermissionsAndObservesCurrentRevocation() {
        jdbc.update("UPDATE ops_harness_grant SET permissions_json=?", "[\"catalog:read\"]");
        var bound =
                service.delegate(
                        "Bearer " + APP,
                        "ops-dev",
                        login,
                        "catalog-run",
                        Instant.now().plusSeconds(600));
        jdbc.update(
                "UPDATE ops_harness_grant SET permissions_json=?",
                "[\"catalog:read\",\"catalog:write\",\"catalog:validate\",\"catalog:publish\"]");
        assertThat(
                        service.current("Bearer " + APP, bound.delegationId(), "catalog-run")
                                .permissions())
                .containsExactly("catalog:read");
        jdbc.update("UPDATE ops_harness_grant SET permissions_json=?", "[\"catalog:write\"]");
        assertThat(
                        service.current("Bearer " + APP, bound.delegationId(), "catalog-run")
                                .permissions())
                .isEmpty();
    }

    @Test
    void runBindingCannotBeRetargetedOrUsedByAnotherApplication() {
        Instant deadline = Instant.now().plusSeconds(600);
        var bound = service.delegate("Bearer " + APP, "ops-dev", login, "run-one", deadline);
        assertThatThrownBy(
                        () ->
                                service.token(
                                        "Bearer " + APP, bound.delegationId(), "run-other", "rag"))
                .hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(
                        () ->
                                service.token(
                                        "Bearer " + APP, bound.delegationId(), "run-one", "auth"))
                .hasMessageContaining("AUDIENCE");
        assertThatThrownBy(
                        () ->
                                service.delegate(
                                        "Bearer " + APP,
                                        "ops-dev",
                                        login,
                                        "run-one",
                                        deadline.plusSeconds(5)))
                .hasMessageContaining("CONFLICT");
        String other = "another-synthetic-application-credential-at-least-thirty-two";
        jdbc.update(
                "INSERT INTO ops_harness_application(application_id,credential_hash,enabled)"
                        + " VALUES(?,?,1)",
                "other",
                HarnessIdentityService.sha256(other));
        assertThatThrownBy(
                        () -> service.current("Bearer " + other, bound.delegationId(), "run-one"))
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void roleAndPermissionElevationCannotExpandExistingDelegation() {
        var bound =
                service.delegate(
                        "Bearer " + APP,
                        "ops-dev",
                        login,
                        "run-one",
                        Instant.now().plusSeconds(600));
        active(List.of("USER", "ADMIN"));
        jdbc.update(
                "UPDATE ops_harness_grant SET permissions_json=?",
                "[\"runs:create\",\"runs:read\",\"runs:admin\",\"opsagent:rag:search\"]");
        assertThat(service.current("Bearer " + APP, bound.delegationId(), "run-one").permissions())
                .doesNotContain("runs:admin");
        var actor =
                new InternalActorTokens(INTERNAL)
                        .verify(
                                service.token(
                                                "Bearer " + APP,
                                                bound.delegationId(),
                                                "run-one",
                                                "rag")
                                        .authorization(),
                                "rag");
        assertThat(actor.roles()).containsExactly("USER");
        jdbc.update("UPDATE ops_harness_grant SET permissions_json='[\"runs:read\"]'");
        assertThat(service.current("Bearer " + APP, bound.delegationId(), "run-one").permissions())
                .containsExactly("runs:read");
        assertThatThrownBy(
                        () ->
                                service.token(
                                        "Bearer " + APP, bound.delegationId(), "run-one", "rag"))
                .hasMessageContaining("RETRIEVAL_DENIED");
    }

    @Test
    void currentUserRoleGrantAndDelegationRevocationFailClosed() {
        var bound =
                service.delegate(
                        "Bearer " + APP,
                        "ops-dev",
                        login,
                        "run-one",
                        Instant.now().plusSeconds(600));
        active(List.of("AUDITOR"));
        assertThatThrownBy(() -> service.current("Bearer " + APP, bound.delegationId(), "run-one"))
                .hasMessageContaining("ROLES_REVOKED");
        active(List.of("USER"));
        jdbc.update("UPDATE ops_harness_grant SET enabled=0");
        assertThatThrownBy(() -> service.current("Bearer " + APP, bound.delegationId(), "run-one"))
                .hasMessageContaining("GRANT_DENIED");
        jdbc.update("UPDATE ops_harness_grant SET enabled=1");
        jdbc.update("UPDATE ops_harness_delegation SET revoked=1");
        assertThatThrownBy(() -> service.current("Bearer " + APP, bound.delegationId(), "run-one"))
                .hasMessageContaining("EXPIRED_OR_REVOKED");
    }

    @Test
    void expiredExecutionDelegationCanReadOutputWithoutBeingRenewedOrUsedToExecute() {
        var bound = outputDelegation();
        long expired = Instant.now().minusSeconds(3600).getEpochSecond();
        jdbc.update("UPDATE ops_harness_delegation SET expires_at=?", expired);
        var before = jdbc.queryForList("SELECT * FROM ops_harness_delegation");
        Instant beforeMint = Instant.now();
        var minted =
                service.outputToken(
                        "Bearer " + APP, bound.delegationId(), "ops-dev", "10", "output-run");
        var verifier = new InternalActorTokens(INTERNAL);
        var actor = verifier.verify(minted.authorization(), "rag-output");
        assertThat(actor.userId()).isEqualTo(10);
        assertThat(actor.runId()).isEqualTo("output-run");
        assertThat(actor.roles()).containsExactly("USER");
        assertThat(actor.validUntil()).isAfter(beforeMint);
        assertThat(actor.validUntil()).isBeforeOrEqualTo(Instant.now().plusSeconds(120));
        assertThat(Instant.parse(minted.expiresAt()))
                .isBeforeOrEqualTo(Instant.now().plusSeconds(120));
        for (String audience : List.of("rag", "knowledge", "auth", "agent")) {
            assertThatThrownBy(() -> verifier.verify(minted.authorization(), audience))
                    .hasMessageContaining("INTERNAL_AUTH_INVALID");
        }
        assertThatThrownBy(
                        () -> service.current("Bearer " + APP, bound.delegationId(), "output-run"))
                .hasMessageContaining("EXPIRED_OR_REVOKED");
        assertThatThrownBy(
                        () ->
                                service.token(
                                        "Bearer " + APP, bound.delegationId(), "output-run", "rag"))
                .hasMessageContaining("EXPIRED_OR_REVOKED");
        assertThat(jdbc.queryForList("SELECT * FROM ops_harness_delegation")).isEqualTo(before);
    }

    @Test
    void historicalOutputMintRequiresOriginalApplicationProjectSubjectAndRun() {
        var bound = outputDelegation();
        assertThatThrownBy(
                        () ->
                                service.outputToken(
                                        "Bearer " + APP,
                                        bound.delegationId(),
                                        "other",
                                        "10",
                                        "output-run"))
                .hasMessageContaining("OUTPUT_SCOPE_DENIED");
        assertThatThrownBy(
                        () ->
                                service.outputToken(
                                        "Bearer " + APP,
                                        bound.delegationId(),
                                        "ops-dev",
                                        "11",
                                        "output-run"))
                .hasMessageContaining("OUTPUT_SCOPE_DENIED");
        assertThatThrownBy(
                        () ->
                                service.outputToken(
                                        "Bearer " + APP,
                                        bound.delegationId(),
                                        "ops-dev",
                                        "10",
                                        "other-run"))
                .hasMessageContaining("NOT_FOUND");
        String other = "another-synthetic-application-credential-at-least-thirty-two";
        jdbc.update(
                "INSERT INTO ops_harness_application(application_id,credential_hash,enabled)"
                        + " VALUES(?,?,1)",
                "other-app",
                HarnessIdentityService.sha256(other));
        assertThatThrownBy(
                        () ->
                                service.outputToken(
                                        "Bearer " + other,
                                        bound.delegationId(),
                                        "ops-dev",
                                        "10",
                                        "output-run"))
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void historicalOutputMintStillObservesDelegationApplicationUserAndGrantRevocation() {
        var bound = outputDelegation();
        jdbc.update("UPDATE ops_harness_delegation SET revoked=1");
        assertOutputDenied(bound, "EXPIRED_OR_REVOKED");
        jdbc.update("UPDATE ops_harness_delegation SET revoked=0");
        jdbc.update("UPDATE ops_harness_application SET enabled=0");
        assertOutputDenied(bound, "APPLICATION_INVALID");
        jdbc.update("UPDATE ops_harness_application SET enabled=1");
        when(auth.actor(10))
                .thenReturn(new AuthService.ActorView(false, 10, "", List.of(), null, null, null));
        assertOutputDenied(bound, "ACTOR_DISABLED");
        active(List.of("USER"));
        jdbc.update("UPDATE ops_harness_grant SET enabled=0");
        assertOutputDenied(bound, "GRANT_DENIED");
        jdbc.update("UPDATE ops_harness_grant SET enabled=1");
        active(List.of("ADMIN"));
        assertOutputDenied(bound, "ROLES_REVOKED");
    }

    @Test
    void historicalOutputRequiresBothPermissionsNowAndWithinTheOriginalPermissionCeiling() {
        var bound = outputDelegation();
        for (String permission : List.of("runs:output:read", "opsagent:rag:search")) {
            jdbc.update(
                    "UPDATE ops_harness_grant SET permissions_json=?", "[\"" + permission + "\"]");
            assertOutputDenied(bound, "OUTPUT_DENIED");
        }
        jdbc.update(
                "UPDATE ops_harness_grant SET permissions_json=?",
                "[\"runs:output:read\",\"opsagent:rag:search\",\"runs:admin\"]");
        for (String permission : List.of("runs:output:read", "opsagent:rag:search")) {
            jdbc.update(
                    "UPDATE ops_harness_delegation SET permissions_json=?",
                    "[\"" + permission + "\"]");
            assertOutputDenied(bound, "OUTPUT_DENIED");
        }
    }

    @Test
    void historicalOutputTokenCannotGainRolesAddedAfterOriginalDelegation() {
        var bound = outputDelegation();
        active(List.of("USER", "ADMIN"));
        var minted =
                service.outputToken(
                        "Bearer " + APP, bound.delegationId(), "ops-dev", "10", "output-run");
        assertThat(
                        new InternalActorTokens(INTERNAL)
                                .verify(minted.authorization(), "rag-output")
                                .roles())
                .containsExactly("USER");
        assertThatThrownBy(
                        () ->
                                service.token(
                                        "Bearer " + APP,
                                        bound.delegationId(),
                                        "output-run",
                                        "rag-output"))
                .hasMessageContaining("AUDIENCE_DENIED");
    }

    @Test
    void historicalOutputTokenCannotOutliveCurrentVisitorLease() {
        Instant expiry = Instant.now().plusSeconds(45);
        when(auth.actor(-20))
                .thenReturn(
                        new AuthService.ActorView(
                                true, -20, "visitor", List.of("DEMO"), expiry, null, null));
        jdbc.update(
                "UPDATE ops_harness_grant SET user_id=-20,required_role='DEMO',permissions_json=?",
                "[\"runs:output:read\",\"opsagent:rag:search\"]");
        var bound =
                service.delegate(
                        "Bearer " + APP,
                        "ops-dev",
                        jwt.issue(-20, "visitor", List.of("DEMO")).token(),
                        "visitor-output",
                        Instant.now().plusSeconds(600));
        jdbc.update(
                "UPDATE ops_harness_delegation SET expires_at=?",
                Instant.now().minusSeconds(60).getEpochSecond());
        var minted =
                service.outputToken(
                        "Bearer " + APP, bound.delegationId(), "ops-dev", "-20", "visitor-output");
        assertThat(Instant.parse(minted.expiresAt())).isEqualTo(expiry);
        assertThat(
                        new InternalActorTokens(INTERNAL)
                                .verify(minted.authorization(), "rag-output")
                                .validUntil())
                .isBeforeOrEqualTo(expiry);
        when(auth.actor(-20))
                .thenReturn(
                        new AuthService.ActorView(
                                true,
                                -20,
                                "visitor",
                                List.of("DEMO"),
                                Instant.now().minusSeconds(1),
                                null,
                                null));
        assertThatThrownBy(
                        () ->
                                service.outputToken(
                                        "Bearer " + APP,
                                        bound.delegationId(),
                                        "ops-dev",
                                        "-20",
                                        "visitor-output"))
                .hasMessageContaining("ACTOR_DISABLED");
        when(auth.actor(-20))
                .thenReturn(
                        new AuthService.ActorView(
                                true, -20, "visitor", List.of("DEMO"), null, null, null));
        assertThatThrownBy(
                        () ->
                                service.outputToken(
                                        "Bearer " + APP,
                                        bound.delegationId(),
                                        "ops-dev",
                                        "-20",
                                        "visitor-output"))
                .hasMessageContaining("ACTOR_DISABLED");
    }

    private HarnessIdentityService.DelegationView outputDelegation() {
        jdbc.update(
                "UPDATE ops_harness_grant SET permissions_json=?",
                "[\"runs:output:read\",\"opsagent:rag:search\"]");
        return service.delegate(
                "Bearer " + APP, "ops-dev", login, "output-run", Instant.now().plusSeconds(600));
    }

    private void assertOutputDenied(HarnessIdentityService.DelegationView bound, String code) {
        assertThatThrownBy(
                        () ->
                                service.outputToken(
                                        "Bearer " + APP,
                                        bound.delegationId(),
                                        "ops-dev",
                                        "10",
                                        "output-run"))
                .hasMessageContaining(code);
    }

    private void active(List<String> roles) {
        when(auth.actor(10))
                .thenReturn(
                        new AuthService.ActorView(
                                true, 10, "synthetic-user", roles, null, null, null));
    }
}
