package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens;
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
 * 实际数据库事务验证发布历史、互斥、CAS请求和最小管理员权限。
 *
 * @author heyu
 * @since 2026/9/3
 */
class ManagedConfigurationTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final DemoTargetClient target = mock(DemoTargetClient.class);
    private final PlatformAuditRepository audit = mock(PlatformAuditRepository.class);
    private final AtomicReference<ObjectNode> remote = new AtomicReference<>();
    private final AtomicInteger publications = new AtomicInteger();
    private JdbcTemplate jdbc;
    private ManagedConfigurationRepository repository;
    private DemoTargetRepository incidents;
    private ManagedConfigurationService service;
    private OpsPrincipal admin;

    @BeforeEach
    void setup() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:managed-configuration-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("demo-target-schema.sql"))
                .execute(source);
        jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new ManagedConfigurationRepository(jdbc, tx, json, audit);
        incidents = new DemoTargetRepository(jdbc, tx);
        service =
                new ManagedConfigurationService(
                        repository, incidents, target, mock(DemoTargetService.class), json);
        admin = actor(1, "admin", "ADMIN");
        remote.set(configuration(content("初始标题", 0), "a".repeat(64), UUID.randomUUID().toString()));
        when(target.managedConfiguration("order-business"))
                .thenAnswer(call -> remote.get().deepCopy());
        when(target.snapshot())
                .thenAnswer(
                        call ->
                                json.createObjectNode()
                                        .put("status", "BASELINE")
                                        .put("configurationStatus", "APPLIED"));
        when(target.preview())
                .thenAnswer(
                        call ->
                                json.createObjectNode()
                                        .put("httpStatus", 200)
                                        .put(
                                                "businessConfigurationRevision",
                                                remote.get().path("revision").asText())
                                        .put(
                                                "catalogTitle",
                                                remote.get()
                                                        .path("content")
                                                        .path("catalogTitle")
                                                        .asText())
                                        .put("catalog", "read from Redis")
                                        .put(
                                                "quotedPrice",
                                                100
                                                        - remote.get()
                                                                .path("content")
                                                                .path("discountPercent")
                                                                .asInt())
                                        .put("internalPassword", "must-not-leak"));
        when(target.publishBusinessConfiguration(any(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            String expected = call.getArgument(1);
                            assertThat(expected).isEqualTo(remote.get().path("revision").asText());
                            publications.incrementAndGet();
                            String requestId = call.getArgument(2);
                            remote.set(
                                    configuration(
                                            call.getArgument(0),
                                            ManagedConfigurationRepository.hash(requestId),
                                            requestId));
                            return remote.get().deepCopy();
                        });
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sourcePublicationIsNotAppliedWhenBusinessEvidenceDoesNotMatch() {
        when(target.preview())
                .thenAnswer(
                        call ->
                                json.createObjectNode()
                                        .put("httpStatus", publications.get() == 0 ? 200 : 503)
                                        .put(
                                                "businessConfigurationRevision",
                                                remote.get().path("revision").asText()));
        var result =
                service.publish(
                        "order-business", request(content("Source accepted", 10), "a".repeat(64)));
        assertThat(result.operation().status()).isEqualTo("PUBLISHED");
        assertThat(result.configuration().business().path("httpStatus").asInt()).isEqualTo(503);
        assertThat(publications).hasValue(1);
    }

    @Test
    void explicitUpstreamCasRejectionIsRecordedAsConflict() {
        doThrow(new DemoTargetClient.ConfigurationRejected("NACOS_CAS_CONFLICT", "changed"))
                .when(target)
                .publishBusinessConfiguration(any(), anyString(), anyString());
        var result =
                service.publish("order-business", request(content("Rejected", 10), "a".repeat(64)));
        assertThat(result.operation().status()).isEqualTo("CONFLICT");
        assertThat(result.configuration().content().path("catalogTitle").asText())
                .isEqualTo("初始标题");
        assertThat(publications).hasValue(0);
    }

    @Test
    void publishesRealCandidateRecordsInitialBaselineAndRollsBackThroughFreshCas() {
        var first =
                service.publish("order-business", request(content("上线后的标题", 10), "a".repeat(64)));
        assertThat(first.operation().status()).isEqualTo("APPLIED");
        assertThat(first.configuration().content().path("catalogTitle").asText())
                .isEqualTo("上线后的标题");
        assertThat(first.configuration().business().path("quotedPrice").asInt()).isEqualTo(90);
        assertThat(first.configuration().business().has("internalPassword")).isFalse();
        var history = service.history("order-business", 1, 10);
        assertThat(history.total()).isEqualTo(2);
        var baseline =
                history.items().stream()
                        .filter(row -> row.action().equals("BASELINE"))
                        .findFirst()
                        .orElseThrow();
        assertThat(baseline.content().path("catalogTitle").asText()).isEqualTo("初始标题");
        var rolled =
                service.rollback(
                        "order-business",
                        new ManagedConfigurationDtos.Rollback(
                                baseline.id(),
                                first.configuration().revision(),
                                UUID.randomUUID().toString(),
                                "核对并恢复初始配置"));
        assertThat(rolled.operation().action()).isEqualTo("ROLLBACK");
        assertThat(rolled.operation().status()).isEqualTo("APPLIED");
        assertThat(rolled.configuration().content()).isEqualTo(baseline.content());
        assertThat(rolled.configuration().revision()).isNotEqualTo(baseline.revision());
        assertThat(publications).hasValue(2);
        assertThat(incidents.configurationBusy()).isFalse();
    }

    @Test
    void duplicateRequestIsReadOnlyAndCannotBeReusedWithOtherContent() {
        var request = request(content("发布标题", 5), "a".repeat(64));
        var first = service.publish("order-business", request);
        var again = service.publish("order-business", request);
        assertThat(again.operation().id()).isEqualTo(first.operation().id());
        assertThat(publications).hasValue(1);
        assertThatThrownBy(
                        () ->
                                service.publish(
                                        "order-business",
                                        new ManagedConfigurationDtos.Publish(
                                                content("其他内容", 5),
                                                request.expectedRevision(),
                                                request.requestId(),
                                                request.comment())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("其他内容");
    }

    @Test
    void staleEditorAndRuntimeConfigurationCannotBePublished() {
        assertThatThrownBy(
                        () ->
                                service.publish(
                                        "order-business",
                                        request(content("新内容", 8), "b".repeat(64))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本已变化");
        assertThatThrownBy(
                        () ->
                                service.publish(
                                        "order-runtime",
                                        request(content("新内容", 8), "a".repeat(64))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只读");
        assertThatThrownBy(() -> service.detail("database-secrets"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未纳入");
        assertThat(publications).hasValue(0);
    }

    @Test
    void unsafeUnknownAndWrongTypeConfigurationFieldsAreRejectedBeforeAnyRemotePublish() {
        for (JsonNode invalid :
                List.of(
                        content("标题", 35),
                        content("标题", -1),
                        content("标题", 5).put("redisPort", 6380),
                        content("标题", 5).put("discountPercent", "10"),
                        content("标题\n越界", 5))) {
            assertThatThrownBy(() -> service.validate("order-business", invalid))
                    .isInstanceOf(BusinessException.class);
        }
        verify(target, never()).publishBusinessConfiguration(any(), anyString(), anyString());
    }

    @Test
    void visitorAndOpsMayReadSafeManagedValuesButCannotWrite() {
        for (String role : List.of("DEMO", "OPS")) {
            actor(-1, "visitor", role);
            assertThat(service.definitions()).hasSize(2);
            assertThat(service.detail("order-business").canPublish()).isFalse();
            assertThat(service.history("order-business", 1, 10).total()).isZero();
            assertThatThrownBy(
                            () ->
                                    service.publish(
                                            "order-business",
                                            request(content("非法变更", 1), "a".repeat(64))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ADMIN");
            assertThatThrownBy(() -> service.validate("order-business", content("非法变更", 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ADMIN");
        }
        assertThat(publications).hasValue(0);
    }

    @Test
    void persistedPublicationGuardBlocksAnotherDemoOwnerAndActiveIncidentBlocksPublication() {
        var request = request(content("发布内容", 1), "a".repeat(64));
        repository.reserve(request, content("初始标题", 0), "PUBLISH", null, "h".repeat(64), admin);
        var visitor =
                new InternalActorTokens.Context(
                        -9,
                        "visitor",
                        List.of("DEMO"),
                        "manual-test",
                        DemoTargetDtos.TARGET,
                        Instant.now().plusSeconds(300));
        assertThatThrownBy(
                        () ->
                                incidents.create(
                                        new DemoTargetDtos.CreateScenario(
                                                "NACOS_REDIS_CONFIG_DRIFT", 300),
                                        visitor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TARGET_CONFIGURATION_BUSY");
        jdbc.update("DELETE FROM operations_managed_config_guard");
        incidents.create(
                new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 300), visitor);
        assertThatThrownBy(
                        () ->
                                service.publish(
                                        "order-business",
                                        request(content("演练中变更", 3), "a".repeat(64))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("演练");
        assertThat(publications).hasValue(0);
    }

    @Test
    void timeoutStaysUnconfirmedUntilMatchingActualAppliedPublicationIsObserved() {
        doThrow(
                        new BusinessException(
                                com.opsagent.common.core.ErrorCode.MIDDLEWARE_UNAVAILABLE,
                                "timeout"))
                .when(target)
                .publishBusinessConfiguration(any(), anyString(), anyString());
        var request = request(content("迟到确认", 4), "a".repeat(64));
        var result = service.publish("order-business", request);
        assertThat(result.operation().status()).isEqualTo("UNCONFIRMED");
        assertThat(incidents.configurationBusy()).isTrue();
        remote.set(configuration(request.content(), "b".repeat(64), request.requestId()));
        service.detail("order-business");
        assertThat(repository.get(result.operation().id()).status()).isEqualTo("APPLIED");
        assertThat(incidents.configurationBusy()).isFalse();
    }

    private OpsPrincipal actor(long id, String username, String role) {
        var principal = new OpsPrincipal(id, username, username, List.of(role));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        return principal;
    }

    private ObjectNode content(String title, int discount) {
        return json.createObjectNode()
                .put("catalogTitle", title)
                .put("notice", "配置中心发布")
                .put("discountPercent", discount);
    }

    private ManagedConfigurationDtos.Publish request(JsonNode content, String expectedRevision) {
        return new ManagedConfigurationDtos.Publish(
                content, expectedRevision, UUID.randomUUID().toString(), "核对演示业务配置后发布");
    }

    private ObjectNode configuration(JsonNode content, String revision, String publication) {
        ObjectNode result =
                json.createObjectNode()
                        .put("revision", revision)
                        .put("appliedRevision", revision)
                        .put("applicationStatus", "APPLIED")
                        .put("nacosStatus", "AVAILABLE")
                        .put("publicationId", publication);
        result.set("content", content);
        return result;
    }
}
