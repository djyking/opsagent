package com.opsagent.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证Nacos真实协议中的CAS、读后确认、独立业务应用与故障互斥。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DemoBusinessSettingsTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final DemoRedis redis = mock(DemoRedis.class);
    private final DemoRuntime runtime =
            new DemoRuntime(redis, json, new SimpleMeterRegistry(), "", "", false);
    private final AtomicReference<String> remote = new AtomicReference<>();
    private final AtomicInteger writes = new AtomicInteger();
    private boolean rejectCas;
    private boolean delayVisibility;

    @AfterEach
    void cleanup() throws Exception {
        runtime.close();
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    void confirmedCasChangesActualRedisBackedBusinessOutputAndRestartReadsSamePublication()
            throws Exception {
        initialize();
        String before = runtime.managedConfiguration("order-business").get("revision").toString();
        String request = UUID.randomUUID().toString();
        JsonNode content = value("配置后的订单目录", 15);

        Map<String, Object> result = runtime.publishBusinessConfiguration(content, before, request);
        when(redis.catalog(6379)).thenReturn("catalog read from real dependency adapter");
        DemoRuntime.BusinessResult business = runtime.preview();

        assertThat(result)
                .containsEntry("applicationStatus", "APPLIED")
                .containsEntry("publicationId", request);
        assertThat(result.get("revision"))
                .isNotEqualTo(before)
                .isEqualTo(result.get("appliedRevision"));
        assertThat(business.httpStatus()).isEqualTo(200);
        assertThat(business.catalogTitle()).isEqualTo("配置后的订单目录");
        assertThat(business.catalog()).isEqualTo("catalog read from real dependency adapter");
        assertThat(business.discountPercent()).isEqualTo(15);
        assertThat(business.basePrice()).isEqualTo(100);
        assertThat(business.quotedPrice()).isEqualTo(85);
        assertThat(business.businessConfigurationRevision()).isEqualTo(result.get("revision"));
        DemoBusinessSettings restarted = new DemoBusinessSettings(json);
        restarted.initialize(service());
        assertThat(restarted.applied().catalogTitle()).isEqualTo("配置后的订单目录");
        assertThat(restarted.appliedRevision()).isEqualTo(result.get("revision"));
        assertThat(writes).hasValue(1);
    }

    @Test
    void rejectsStaleRevisionAndActualNacosCasRaceWithoutApplyingCandidate() throws Exception {
        initialize();
        String original = settings().appliedRevision();
        assertThatThrownBy(
                        () ->
                                runtime.publishBusinessConfiguration(
                                        value("新标题", 1),
                                        "0".repeat(64),
                                        UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REVISION_CONFLICT");
        assertThat(writes).hasValue(0);
        rejectCas = true;
        assertThatThrownBy(
                        () ->
                                runtime.publishBusinessConfiguration(
                                        value("新标题", 1), original, UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NACOS_CAS_CONFLICT");
        assertThat(settings().appliedRevision()).isEqualTo(original);
        assertThat(settings().applied().catalogTitle()).isEqualTo("初始订单");
    }

    @Test
    void pendingAcknowledgementRetainsOldAppliedValueAndDoesNotClaimSuccess() throws Exception {
        initialize();
        String original = settings().appliedRevision();
        delayVisibility = true;
        Instant start = Instant.now();
        assertThatThrownBy(
                        () ->
                                runtime.publishBusinessConfiguration(
                                        value("未确认标题", 2), original, UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CONFIG_CONFIRMATION_PENDING");
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
        assertThat(settings().appliedRevision()).isEqualTo(original);
        assertThat(settings().applied().catalogTitle()).isEqualTo("初始订单");
    }

    @Test
    void currentFaultAndUnknownFieldsCannotBeUsedToChangeRuntimeOrBusiness() throws Exception {
        initialize();
        String revision = settings().appliedRevision();
        JsonNode invalid = value("任意连接", 3);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).put("redisPort", 6380);
        assertThatThrownBy(
                        () ->
                                runtime.publishBusinessConfiguration(
                                        invalid, revision, UUID.randomUUID().toString()))
                .isInstanceOf(IllegalArgumentException.class);
        runtime.accept(
                json.writeValueAsString(
                        DemoConfiguration.fault(
                                UUID.randomUUID().toString(),
                                "SENTINEL_RULE_REGRESSION",
                                Instant.now().plusSeconds(300))));
        assertThatThrownBy(
                        () ->
                                runtime.publishBusinessConfiguration(
                                        value("故障期间变更", 2), revision, UUID.randomUUID().toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TARGET_BUSY");
        assertThat(writes).hasValue(0);
        assertThat(runtime.snapshot()).containsEntry("status", "FAULT_ACTIVE");
        assertThat(FlowRuleManager.getRules().get(0).getCount()).isZero();
    }

    @Test
    void publicationIdIsIdempotentButRollbackToSameContentHasFreshRevision() throws Exception {
        initialize();
        String original = settings().appliedRevision();
        String request = UUID.randomUUID().toString();
        JsonNode content = value("修改后的订单", 20);
        var first = runtime.publishBusinessConfiguration(content, original, request);
        var duplicate = runtime.publishBusinessConfiguration(content, original, request);
        assertThat(duplicate.get("revision")).isEqualTo(first.get("revision"));
        assertThat(writes).hasValue(1);
        var rollback =
                runtime.publishBusinessConfiguration(
                        value("初始订单", 0),
                        first.get("revision").toString(),
                        UUID.randomUUID().toString());
        assertThat(rollback.get("revision"))
                .isNotEqualTo(original)
                .isNotEqualTo(first.get("revision"));
        assertThat(settings().applied().discountPercent()).isZero();
    }

    @Test
    void firstInitializationUsesNonblankEmptyContentDigestForCreationCas() throws Exception {
        settings().initialize(service());
        assertThat(writes).hasValue(1);
        assertThat(remote.get()).contains("OpsAgent 演示订单");
        assertThat(settings().view()).containsEntry("applicationStatus", "APPLIED");
    }

    private void initialize() throws Exception {
        remote.set(
                json.writeValueAsString(
                        Map.of(
                                "publicationId",
                                UUID.randomUUID().toString(),
                                "configuration",
                                value("初始订单", 0))));
        settings().initialize(service());
        runtime.accept(json.writeValueAsString(DemoConfiguration.baseline("", "BASELINE")));
    }

    private DemoBusinessSettings settings() {
        return (DemoBusinessSettings) ReflectionTestUtils.getField(runtime, "businessSettings");
    }

    private JsonNode value(String title, int discount) {
        return json.createObjectNode()
                .put("catalogTitle", title)
                .put("notice", "来自受控业务配置")
                .put("discountPercent", discount);
    }

    private ConfigService service() {
        return (ConfigService)
                Proxy.newProxyInstance(
                        ConfigService.class.getClassLoader(),
                        new Class<?>[] {ConfigService.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("getConfig")) {
                                assertThat(arguments[0]).isEqualTo(DemoBusinessSettings.DATA_ID);
                                assertThat(arguments[1]).isEqualTo("OPSAGENT_DEMO");
                                return remote.get();
                            }
                            if (method.getName().equals("publishConfigCas")) {
                                assertThat(arguments[0]).isEqualTo(DemoBusinessSettings.DATA_ID);
                                assertThat(arguments[1]).isEqualTo("OPSAGENT_DEMO");
                                assertThat(arguments[3])
                                        .isEqualTo(
                                                DemoBusinessSettings.digest(
                                                        "MD5",
                                                        remote.get() == null ? "" : remote.get()));
                                assertThat(arguments[4]).isEqualTo("json");
                                writes.incrementAndGet();
                                if (rejectCas) return false;
                                if (!delayVisibility) remote.set(arguments[2].toString());
                                return true;
                            }
                            if (method.getName().equals("shutDown")) return null;
                            throw new AssertionError(
                                    "Unexpected Nacos operation: " + method.getName());
                        });
    }
}
