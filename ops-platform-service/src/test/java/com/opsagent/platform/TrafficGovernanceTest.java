package com.opsagent.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * H2-backed governed changes: role boundaries, durable audit, CAS, rollback and degraded
 * integrations.
 *
 * @author heyu
 * @since 2026/9/3
 */
class TrafficGovernanceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final NacosConfigurationClient nacos = mock(NacosConfigurationClient.class);
    private final SentinelTrafficClient runtime = mock(SentinelTrafficClient.class);
    private final PlatformAuditRepository audit = mock(PlatformAuditRepository.class);
    private final TrafficRuleValidator validator = new TrafficRuleValidator(json);
    private final AtomicReference<String> remote = new AtomicReference<>("");
    private final AtomicReference<JsonNode> applied = new AtomicReference<>();
    private final AtomicInteger writes = new AtomicInteger();
    private TrafficGovernanceService service;
    private TrafficChangeRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:traffic-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("traffic-governance-schema.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        repository =
                new TrafficChangeRepository(
                        jdbc,
                        json,
                        new TransactionTemplate(new DataSourceTransactionManager(source)),
                        audit);
        service = new TrafficGovernanceService(nacos, runtime, validator, repository, json);
        actor("ADMIN");
        applied.set(json.createArrayNode());
        when(nacos.ruleContent(anyString(), anyString())).thenAnswer(call -> content(remote.get()));
        when(runtime.snapshot()).thenAnswer(call -> snapshot("AVAILABLE", applied.get()));
        when(nacos.compareAndPublish(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            writes.incrementAndGet();
                            String before = call.getArgument(2);
                            String after = call.getArgument(3);
                            if (!remote.compareAndSet(before, after)) return false;
                            applied.set(json.readTree(after));
                            return true;
                        });
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void opsAndDemoCannotValidatePublishOrRollbackEvenWhenCallingServiceDirectly()
            throws Exception {
        for (String role : List.of("OPS", "DEMO")) {
            actor(role);
            assertThatThrownBy(() -> service.validate("FLOW", rules(5)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("管理员");
            assertThatThrownBy(() -> service.publish("FLOW", request(rules(5))))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(
                            () ->
                                    service.rollback(
                                            "FLOW",
                                            new TrafficGovernanceDtos.Rollback(
                                                    1,
                                                    revision(),
                                                    UUID.randomUUID().toString(),
                                                    "rollback")))
                    .isInstanceOf(BusinessException.class);
            assertThat(service.ruleSet("FLOW").editable()).isFalse();
        }
        assertThat(writes).hasValue(0);
        verify(audit, never())
                .addPlatform(anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void publicationIsDurableBeforeRemoteWriteAndReplayDoesNotPublishTwice() throws Exception {
        var request = request(rules(5));
        doAnswer(
                        call -> {
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM traffic_governance_change"
                                                            + " WHERE status='REQUESTED'",
                                                    Integer.class))
                                    .isEqualTo(1);
                            writes.incrementAndGet();
                            remote.set(call.getArgument(3));
                            applied.set(json.readTree(remote.get()));
                            return true;
                        })
                .when(nacos)
                .compareAndPublish(anyString(), anyString(), anyString(), anyString());
        var result = service.publish("FLOW", request);
        assertThat(result.operation().status()).isEqualTo("APPLIED");
        assertThat(service.publish("FLOW", request).operation().id())
                .isEqualTo(result.operation().id());
        assertThat(writes).hasValue(1);
        verify(audit)
                .addPlatform(
                        eq("SENTINEL_RULE"),
                        anyString(),
                        eq("TRAFFIC_RULE_REQUESTED"),
                        eq(1L),
                        anyString());
        verify(audit)
                .addPlatform(
                        eq("SENTINEL_RULE"),
                        anyString(),
                        eq("TRAFFIC_RULE_APPLIED"),
                        eq(1L),
                        anyString());
        var changed =
                new TrafficGovernanceDtos.Publish(
                        rules(9),
                        request.expectedRevision(),
                        request.requestId(),
                        request.comment());
        assertThatThrownBy(() -> service.publish("FLOW", changed))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求标识");
    }

    @Test
    void staleRevisionAndRemoteCasRejectionPreserveOtherPublishersRules() throws Exception {
        var request = request(rules(5));
        remote.set(rules(8).toString());
        assertThatThrownBy(() -> service.publish("FLOW", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("规则已变化");
        assertThat(writes).hasValue(0);
        var nextRequest = request(rules(6));
        doReturn(false)
                .when(nacos)
                .compareAndPublish(anyString(), anyString(), anyString(), anyString());
        var result = service.publish("FLOW", nextRequest);
        assertThat(result.operation().status()).isEqualTo("REJECTED");
        assertThat(remote.get()).isEqualTo(rules(8).toString());
    }

    @Test
    void timeoutAfterMutationRemainsUnconfirmedAndExactRetryDoesNotWriteAgain() throws Exception {
        var request = request(rules(5));
        doAnswer(
                        call -> {
                            writes.incrementAndGet();
                            remote.set(call.getArgument(3));
                            throw new IllegalStateException("private-network-detail");
                        })
                .when(nacos)
                .compareAndPublish(anyString(), anyString(), anyString(), anyString());
        var result = service.publish("FLOW", request);
        assertThat(result.operation().status()).isEqualTo("UNCONFIRMED");
        assertThat(json.writeValueAsString(result)).doesNotContain("private-network-detail");
        service.publish("FLOW", request);
        assertThat(writes).hasValue(1);
    }

    @Test
    void publishedButNotYetLoadedRulesAreConfirmedOnLaterReadAndCanRollback() throws Exception {
        doAnswer(
                        call -> {
                            remote.set(call.getArgument(3));
                            return true;
                        })
                .when(nacos)
                .compareAndPublish(anyString(), anyString(), anyString(), anyString());
        var first = service.publish("FLOW", request(rules(5)));
        assertThat(first.operation().status()).isEqualTo("PUBLISHED");
        applied.set(json.readTree(remote.get()));
        assertThat(service.ruleSet("FLOW").applicationStatus()).isEqualTo("APPLIED");
        assertThat(repository.get(first.operation().id()).status()).isEqualTo("APPLIED");
        var second = service.publish("FLOW", request(rules(8)));
        applied.set(json.readTree(remote.get()));
        service.ruleSet("FLOW");
        assertThat(repository.get(second.operation().id()).status()).isEqualTo("APPLIED");
        var rolled =
                service.rollback(
                        "FLOW",
                        new TrafficGovernanceDtos.Rollback(
                                first.operation().id(),
                                revision(),
                                UUID.randomUUID().toString(),
                                "恢复已确认版本"));
        assertThat(rolled.operation().action()).isEqualTo("ROLLBACK");
        assertThat(rolled.operation().rollbackVersionId()).isEqualTo(first.operation().id());
        assertThat(json.readTree(remote.get()).get(0).path("count").asDouble()).isEqualTo(5);
    }

    @Test
    void sourcesDegradeIndependentlyAndUnintegratedServiceMetricsAreUnknown() throws Exception {
        when(nacos.ruleContent(anyString(), anyString()))
                .thenThrow(new IllegalStateException("private-error"));
        var set = service.ruleSet("FLOW");
        assertThat(set.status()).isEqualTo("UNAVAILABLE");
        assertThat(set.editable()).isFalse();
        assertThat(service.summary("ops-rag-service").status()).isEqualTo("AVAILABLE");
        var other = service.summary("redis");
        assertThat(other.status()).isEqualTo("NOT_INTEGRATED");
        assertThat(other.ruleCount()).isNull();
        assertThat(other.blockQps()).isNull();
        when(runtime.snapshot()).thenReturn(snapshot("UNAVAILABLE", json.createArrayNode()));
        assertThat(service.summary(null).passQps()).isNull();
        assertThatThrownBy(() -> service.publish("FLOW", request(rules(5))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("客户端不可读");
    }

    @Test
    void validatorsRejectUnsafeOrUnsupportedRulesAndKeepRealStrategies() throws Exception {
        assertThatThrownBy(() -> validator.validate("PARAM_FLOW", rules(5)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "FLOW",
                                        json.readTree(
                                                "[{\"resource\":\"private-service\",\"count\":5}]")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "FLOW",
                                        json.readTree(
                                                "[{\"resource\":\"ops-rag-request\",\"count\":5,\"password\":\"x\"}]")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        "DEGRADE",
                                        json.readTree(
                                                "[{\"resource\":\"ops-rag-ask\",\"count\":5}]")))
                .isInstanceOf(BusinessException.class);
        var degrade =
                validator.validate(
                        "DEGRADE",
                        json.readTree(
                                "[{\"resource\":\"ops-rag-request\",\"grade\":1,\"count\":0.5,\"timeWindow\":10}]"));
        assertThat(degrade.get(0).path("statIntervalMs").asInt()).isEqualTo(1000);
        assertThat(
                        validator
                                .validate("SYSTEM", json.readTree("[{\"highestCpuUsage\":0.8}]"))
                                .size())
                .isEqualTo(1);
    }

    private void actor(String role) {
        var actor = new OpsPrincipal(1, "tester", "test", List.of(role));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    private String revision() {
        return NacosConfigurationClient.sha256(remote.get());
    }

    private JsonNode rules(int qps) throws Exception {
        return json.readTree(
                "[{\"resource\":\"ops-rag-request\",\"grade\":1,\"count\":" + qps + "}]");
    }

    private TrafficGovernanceDtos.Publish request(JsonNode rules) {
        return new TrafficGovernanceDtos.Publish(
                rules, revision(), UUID.randomUUID().toString(), "经核对调整业务容量");
    }

    private NacosConfigurationClient.Content content(String value) {
        return new NacosConfigurationClient.Content(
                value, "json", NacosConfigurationClient.sha256(value), !value.isBlank());
    }

    private SentinelTrafficClient.Snapshot snapshot(String status, JsonNode rules) {
        return new SentinelTrafficClient.Snapshot(
                status, List.of(), json.createObjectNode().set("FLOW", rules), Instant.now());
    }
}
