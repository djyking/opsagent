package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 验证配置确认和业务恢复独立判断，访客不能跨所属资源操作。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DemoTargetServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final FakeTarget target = new FakeTarget(json);
    private final InternalActorTokens.Context actor =
            new InternalActorTokens.Context(
                    -101,
                    "visitor",
                    List.of("DEMO"),
                    "manual-fixed-run",
                    DemoTargetDtos.TARGET,
                    Instant.now().plusSeconds(600));
    private DemoTargetService service;

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:demo-service-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("demo-target-schema.sql"))
                .execute(source);
        var repository =
                new DemoTargetRepository(
                        new JdbcTemplate(source),
                        new TransactionTemplate(new DataSourceTransactionManager(source)));
        service =
                new DemoTargetService(
                        repository,
                        target,
                        json,
                        new SimpleMeterRegistry(),
                        new DemoTargetEvidenceRepository(new JdbcTemplate(source), json));
    }

    @Test
    void configurationAcknowledgementDoesNotCountAsRecoveredWhenRealProbeStillFails() {
        var incident =
                service.create(
                        new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 600), actor);
        target.failAfterRestore = true;
        var response =
                service.action(
                        new DemoTargetDtos.Action(
                                incident.incidentId(),
                                "RESTORE_CONFIGURATION",
                                incident.expectedRevision(),
                                "manual-action-1"),
                        actor);
        assertThat(response.path("actionAccepted").asBoolean()).isTrue();
        assertThat(response.path("recoveryVerified").asBoolean()).isFalse();
        assertThat(response.path("business").path("httpStatus").asInt()).isEqualTo(503);
    }

    @Test
    void manualRecoveryIsOwnedIdempotentAndNeverLabeledAsAgentRepair() {
        var incident =
                service.create(
                        new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 600), actor);
        var request =
                new DemoTargetDtos.Action(
                        incident.incidentId(),
                        "RESTORE_CONFIGURATION",
                        incident.expectedRevision(),
                        "manual-action-2");
        var other =
                new InternalActorTokens.Context(
                        -102,
                        "another",
                        List.of("DEMO"),
                        "other-run",
                        DemoTargetDtos.TARGET,
                        Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> service.action(request, other))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("NOT_OWNED");
        var response = service.action(request, actor);
        assertThat(response.path("recoveryVerified").asBoolean()).isTrue();
        assertThat(response.path("recoverySource").asText()).isEqualTo("MANUAL");
        assertThat(response.path("agentRecovered").asBoolean()).isFalse();
        service.action(request, actor);
        assertThat(target.restoreCalls).isEqualTo(1);
    }

    @Test
    void recoveredIncidentGetsIndependentLiveVerificationWithoutRewritingItsHistory() {
        var incident =
                service.create(
                        new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 600), actor);
        var evidence = service.evidence(incident.incidentId(), actor);
        assertThat(evidence.path("current").asBoolean()).isTrue();
        assertThat(evidence.path("snapshot").has("scenarioCode")).isFalse();
        assertThat(evidence.path("incident").has("scenarioCode")).isFalse();
        assertThat(evidence.path("observations")).isNotEmpty();
        service.action(
                new DemoTargetDtos.Action(
                        incident.incidentId(),
                        "RESTORE_CONFIGURATION",
                        incident.expectedRevision(),
                        "evidence-recovery"),
                actor);
        var history = service.evidence(incident.incidentId(), actor);
        assertThat(history.path("current").asBoolean()).isTrue();
        assertThat(history.path("captureStatus").asText()).isEqualTo("LIVE_OBSERVATION");
        assertThat(history.path("snapshot").path("business").path("httpStatus").asInt())
                .isEqualTo(200);
        assertThat(history.path("snapshot").path("incidentId").asText())
                .isEqualTo(incident.incidentId());
        assertThat(history.path("snapshot").path("observedAt").asText()).isNotBlank();
        assertThat(history.path("incident").path("status").asText()).isEqualTo("RECOVERED");
        assertThat(history.path("incident").path("recoverySource").asText()).isEqualTo("MANUAL");
        assertThat(history.path("observations")).hasSize(2);
        // A later, unrelated dependency failure is background evidence, not part of the
        // completed incident whose ID is still retained by the runtime for provenance.
        target.httpStatus = 503;
        service.snapshot(actor);
        var afterBackgroundFailure = service.evidence(incident.incidentId(), actor);
        assertThat(afterBackgroundFailure.path("current").asBoolean()).isTrue();
        assertThat(
                        afterBackgroundFailure
                                .path("snapshot")
                                .path("business")
                                .path("httpStatus")
                                .asInt())
                .isEqualTo(503);
        assertThat(afterBackgroundFailure.path("observations")).hasSize(2);
        assertThat(afterBackgroundFailure.path("observations"))
                .isEqualTo(history.path("observations"));
        assertThat(afterBackgroundFailure.path("observations").get(0).path("httpStatus").asInt())
                .isEqualTo(200);
    }

    @Test
    void anotherCurrentIncidentCannotVerifyAnOldRecoveredIncident() {
        var incident =
                service.create(
                        new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 600), actor);
        service.action(
                new DemoTargetDtos.Action(
                        incident.incidentId(),
                        "RESTORE_CONFIGURATION",
                        incident.expectedRevision(),
                        "switch-recovery"),
                actor);
        var completed = service.evidence(incident.incidentId(), actor);
        target.state.put("incidentId", UUID.randomUUID().toString());
        var historical = service.evidence(incident.incidentId(), actor);
        assertThat(historical.path("current").asBoolean()).isFalse();
        assertThat(historical.path("captureStatus").asText()).isEqualTo("HISTORICAL");
        assertThat(historical.path("snapshot").isNull()).isTrue();
        assertThat(historical.path("observations")).isEqualTo(completed.path("observations"));
        assertThat(historical.path("incident")).isEqualTo(completed.path("incident"));
    }

    @Test
    void unavailableLiveTargetNeverFallsBackToHistoricalSuccessAsCurrentEvidence() {
        var incident =
                service.create(
                        new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 600), actor);
        service.action(
                new DemoTargetDtos.Action(
                        incident.incidentId(),
                        "RESTORE_CONFIGURATION",
                        incident.expectedRevision(),
                        "unavailable-recovery"),
                actor);
        var completed = service.evidence(incident.incidentId(), actor);
        target.unavailable = true;
        var unavailable = service.evidence(incident.incidentId(), actor);
        assertThat(unavailable.path("current").asBoolean()).isFalse();
        assertThat(unavailable.path("captureStatus").asText()).isEqualTo("LIVE_TARGET_UNAVAILABLE");
        assertThat(unavailable.path("snapshot").isNull()).isTrue();
        assertThat(unavailable.path("observations")).isEqualTo(completed.path("observations"));
    }

    @Test
    void sameOwnerCannotOperateOrReadEvidenceUsingTheOtherTargetContext() {
        var incident =
                service.create(
                        new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 600), actor);
        var wrongTarget =
                new InternalActorTokens.Context(
                        actor.userId(),
                        actor.username(),
                        actor.roles(),
                        actor.runId(),
                        DemoTargetDtos.NOTIFICATION_TARGET,
                        actor.validUntil());
        assertThatThrownBy(() -> service.evidence(incident.incidentId(), wrongTarget))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TARGET_MISMATCH");
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new DemoTargetDtos.CreateScenario(
                                                "NACOS_REDIS_CONFIG_DRIFT", 600),
                                        wrongTarget))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("INVALID_DEMO_SCENARIO");
    }

    /**
     * @author heyu
     */
    private static final class FakeTarget extends DemoTargetClient {
        private final ObjectMapper json;
        private final ObjectNode state;
        private int httpStatus = 200;
        private boolean failAfterRestore;
        private boolean unavailable;
        private int restoreCalls;

        FakeTarget(ObjectMapper json) {
            super(json, "http://fixed-test-target", "test-control-token-not-a-production-secret");
            this.json = json;
            state =
                    json.createObjectNode()
                            .put("scope", "ISOLATED_DEMO")
                            .put("targetCode", DemoTargetDtos.TARGET)
                            .put("incidentId", "")
                            .put("status", "BASELINE")
                            .put("appliedRevision", "a".repeat(64))
                            .put("configurationStatus", "APPLIED")
                            .put("recoverySource", "BASELINE");
        }

        @Override
        ObjectNode snapshot() {
            if (unavailable)
                throw new BusinessException(
                        ErrorCode.MIDDLEWARE_UNAVAILABLE, "LIVE_TARGET_UNAVAILABLE");
            return state.deepCopy();
        }

        @Override
        ObjectNode preview() {
            return json.createObjectNode()
                    .put("httpStatus", httpStatus)
                    .put(
                            "reasonCode",
                            httpStatus == 200 ? "ORDER_PREVIEW_READY" : "REDIS_CONNECT_FAILED");
        }

        @Override
        void inject(DemoTargetDtos.Incident incident) {
            state.put("incidentId", incident.incidentId())
                    .put("status", "FAULT_ACTIVE")
                    .put("appliedRevision", "b".repeat(64))
                    .put("scenarioCode", incident.scenarioCode())
                    .put("recoverySource", "");
            httpStatus = 503;
        }

        @Override
        void restore(DemoTargetDtos.Action request, String source) {
            restoreCalls++;
            state.put("status", "BASELINE")
                    .put("appliedRevision", "c".repeat(64))
                    .put("recoverySource", source);
            httpStatus = failAfterRestore ? 503 : 200;
        }
    }
}
