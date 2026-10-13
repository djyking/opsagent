package com.opsagent.demo;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.naming.NamingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Nacos 持久配置驱动真实 Redis 端口和 Sentinel 规则，TTL 守护独立于 Agent 存活。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class DemoRuntime implements ApplicationRunner {
    private static final String DATA_ID = "ops-demo-order-runtime.json";
    private static final String GROUP = "OPSAGENT_DEMO";
    private final DemoRedis redis;
    private final ObjectMapper json;
    private final Counter passed;
    private final Counter blocked;
    private final Counter errors;
    private final ExecutorService configEvents = Executors.newSingleThreadExecutor();
    private final String address;
    private final String namespace;
    private final boolean nacosEnabled;
    private ConfigService config;
    private NamingService naming;
    private volatile DemoConfiguration current = DemoConfiguration.baseline("", "BASELINE");
    private volatile String configurationStatus = "INITIALIZING";
    private volatile int lastHttpStatus;
    private volatile String lastReason = "NOT_PROBED";
    private volatile Instant lastObserved;
    private volatile boolean baselineNeedsPublish;
    private final DemoBusinessSettings businessSettings;
    private DemoNotificationRuntime notifications;
    private volatile Instant configurationAppliedAt = Instant.now();
    private volatile Map<String, Object> previousConfiguration = Map.of();

    @org.springframework.beans.factory.annotation.Autowired
    void notificationRuntime(DemoNotificationRuntime value) {
        notifications = value;
    }

    DemoRuntime(
            DemoRedis redis,
            ObjectMapper json,
            MeterRegistry metrics,
            @Value("${ops.demo.nacos-address}") String address,
            @Value("${ops.demo.nacos-namespace:}") String namespace,
            @Value("${ops.demo.nacos-enabled:true}") boolean enabled) {
        this.redis = redis;
        this.json = json;
        this.businessSettings = new DemoBusinessSettings(json);
        this.address = address;
        this.namespace = namespace;
        this.nacosEnabled = enabled;
        passed = metrics.counter("opsagent.demo.business.requests", "outcome", "success");
        blocked = metrics.counter("opsagent.demo.business.requests", "outcome", "limited");
        errors = metrics.counter("opsagent.demo.business.requests", "outcome", "dependency_error");
        apply(current);
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        if (!nacosEnabled) {
            configurationStatus = "DISABLED";
            return;
        }
        Properties properties = new Properties();
        properties.setProperty("serverAddr", address);
        properties.setProperty("namespace", namespace);
        properties.setProperty("namingLoadCacheAtStart", "false");
        config = NacosFactory.createConfigService(properties);
        String content = config.getConfig(DATA_ID, GROUP, 3000);
        if (content == null || content.isBlank()) publish(current);
        else accept(content);
        config.addListener(
                DATA_ID,
                GROUP,
                new Listener() {
                    @Override
                    public Executor getExecutor() {
                        return configEvents;
                    }

                    @Override
                    public void receiveConfigInfo(String content) {
                        try {
                            accept(config.getConfig(DATA_ID, GROUP, 3000));
                        } catch (Exception ignored) {
                            configurationStatus = "INVALID_CONFIGURATION";
                        }
                    }
                });
        try {
            businessSettings.initialize(config);
        } catch (Exception ignored) {
            businessSettings.invalid();
        }
        config.addListener(
                DemoBusinessSettings.DATA_ID,
                GROUP,
                new Listener() {
                    @Override
                    public Executor getExecutor() {
                        return configEvents;
                    }

                    @Override
                    public void receiveConfigInfo(String content) {
                        refreshBusinessConfiguration();
                    }
                });
        naming = NacosFactory.createNamingService(properties);
        naming.registerInstance(
                DemoConfiguration.TARGET, InetAddress.getLocalHost().getHostAddress(), 8110);
        redis.seed();
        guard();
        if (notifications != null) notifications.initialize(config, naming, configEvents);
    }

    synchronized Map<String, Object> inject(String incident, String scenario, Instant expiresAt)
            throws Exception {
        guard();
        if (businessSettings.applicationPaused()) throw new IllegalStateException("TARGET_BUSY");
        if (current.faulted()) {
            if (current.incidentId().equals(incident) && current.scenarioCode().equals(scenario))
                return snapshot();
            throw new IllegalStateException("TARGET_BUSY");
        }
        if (!"APPLIED".equals(configurationStatus))
            throw new IllegalStateException("NACOS_NOT_READY");
        publish(DemoConfiguration.fault(incident, scenario, expiresAt));
        return snapshot();
    }

    synchronized Map<String, Object> restore(
            String incident, String action, String expectedRevision, String source)
            throws Exception {
        if (!List.of("AGENT_TOOL", "MANUAL").contains(source)) {
            throw new IllegalArgumentException("INVALID_ACTOR_SOURCE");
        }
        String expectedAction =
                current.scenarioCode().equals("NACOS_REDIS_CONFIG_DRIFT")
                        ? "RESTORE_CONFIGURATION"
                        : "RESTORE_FLOW_RULE";
        if (!current.incidentId().equals(incident))
            throw new IllegalArgumentException("INCIDENT_MISMATCH");
        if (!current.faulted()) return snapshot();
        if (!current.revision().equals(expectedRevision))
            throw new IllegalStateException("REVISION_CONFLICT");
        if (!expectedAction.equals(action))
            throw new IllegalArgumentException("ACTION_SCENARIO_MISMATCH");
        publish(DemoConfiguration.baseline(incident, source));
        return snapshot();
    }

    synchronized void accept(String content) throws Exception {
        if (content == null || content.length() > 4096)
            throw new IllegalArgumentException("INVALID_CONFIG_SIZE");
        DemoConfiguration value = json.readValue(content, DemoConfiguration.class);
        if (!value.valid()) throw new IllegalArgumentException("INVALID_DEMO_CONFIG");
        if (value.faulted() && value.expiresAtEpoch() <= Instant.now().getEpochSecond()) {
            apply(DemoConfiguration.baseline(value.incidentId(), "TTL_GUARD"));
            baselineNeedsPublish = true;
        } else {
            apply(value);
        }
        configurationStatus = "APPLIED";
    }

    private void publish(DemoConfiguration value) throws Exception {
        if (config == null) throw new IllegalStateException("NACOS_NOT_READY");
        long started = System.nanoTime();
        long budget = TimeUnit.SECONDS.toNanos(6);
        if (!config.publishConfig(DATA_ID, GROUP, json.writeValueAsString(value), "json")) {
            throw new IllegalStateException("CONFIG_PUBLISH_REJECTED");
        }
        configurationStatus = "NACOS_CONFIRMATION_PENDING";
        // The ACK can precede Nacos read visibility. Never apply a stale value while confirming
        // this write.
        // The six-second budget includes publish time, leaving room inside the platform's
        // eight-second request.
        while (System.nanoTime() - started < budget) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(budget - (System.nanoTime() - started));
            if (remaining < 1) break;
            String content = null;
            try {
                content = config.getConfig(DATA_ID, GROUP, Math.min(500, remaining));
            } catch (com.alibaba.nacos.api.exception.NacosException ignored) {
                // A failed read is not evidence that the acknowledged publish was rejected.
            }
            if (content != null && !content.isBlank() && content.length() <= 4096) {
                DemoConfiguration observed = null;
                try {
                    observed = json.readValue(content, DemoConfiguration.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // A concurrent or incomplete read cannot replace the last applied runtime
                    // configuration.
                }
                if (observed != null
                        && observed.valid()
                        && observed.revision().equals(value.revision())) {
                    accept(content);
                    if (!current.revision().equals(value.revision())) {
                        throw new IllegalStateException("CONFIG_NOT_APPLIED");
                    }
                    return;
                }
            }
            remaining = TimeUnit.NANOSECONDS.toMillis(budget - (System.nanoTime() - started));
            if (remaining < 1) break;
            try {
                Thread.sleep(Math.min(100, remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
        throw new IllegalStateException("CONFIG_CONFIRMATION_PENDING");
    }

    private void apply(DemoConfiguration value) {
        if (!current.revision().equals(value.revision())) {
            previousConfiguration = configurationProjection(current);
            configurationAppliedAt = Instant.now();
        }
        FlowRule rule = new FlowRule(DemoConfiguration.RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(value.qps());
        FlowRuleManager.loadRules(List.of(rule));
        current = value;
    }

    @Scheduled(fixedDelay = 1000)
    synchronized void guard() {
        try {
            businessSettings.recoverExpiredPause();
        } catch (Exception ignored) {
            businessSettings.invalid();
        }
        if (current.faulted() && current.expiresAtEpoch() <= Instant.now().getEpochSecond()) {
            apply(DemoConfiguration.baseline(current.incidentId(), "TTL_GUARD"));
            baselineNeedsPublish = true;
        }
        if (baselineNeedsPublish && config != null) {
            try {
                publish(current);
                baselineNeedsPublish = false;
            } catch (Exception ignored) {
                configurationStatus = "LOCAL_TTL_RECOVERY_PENDING_NACOS";
            }
        }
    }

    private synchronized void refreshBusinessConfiguration() {
        if (current.faulted()) {
            businessSettings.deferred();
            return;
        }
        try {
            businessSettings.accept(config.getConfig(DemoBusinessSettings.DATA_ID, GROUP, 1000));
        } catch (Exception ignored) {
            businessSettings.invalid();
        }
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 30000)
    void refreshBusiness() {
        if (config != null) refreshBusinessConfiguration();
    }

    synchronized Map<String, Object> managedConfiguration(String id) throws Exception {
        if (id.equals("order-business")) return businessConfigurationView();
        if (!id.equals("order-runtime"))
            throw new IllegalArgumentException("CONFIGURATION_NOT_MANAGED");
        if (config == null) throw new IllegalStateException("NACOS_NOT_READY");
        String raw = config.getConfig(DATA_ID, GROUP, 1500);
        if (raw == null || raw.length() > 4096)
            throw new IllegalStateException("NACOS_CONFIGURATION_UNAVAILABLE");
        DemoConfiguration observed = json.readValue(raw, DemoConfiguration.class);
        if (!observed.valid()) throw new IllegalStateException("INVALID_DEMO_CONFIG");
        return Map.of(
                "content",
                observed,
                "revision",
                observed.revision(),
                "appliedRevision",
                current.revision(),
                "applicationStatus",
                configurationStatus,
                "nacosStatus",
                "AVAILABLE",
                "observedAt",
                Instant.now());
    }

    synchronized Map<String, Object> publishBusinessConfiguration(
            JsonNode content, String expectedRevision, String requestId) throws Exception {
        guard();
        if (current.faulted()) throw new IllegalStateException("TARGET_BUSY");
        if (!"APPLIED".equals(configurationStatus))
            throw new IllegalStateException("NACOS_NOT_READY");
        businessSettings.publish(content, expectedRevision, requestId);
        return businessConfigurationView();
    }

    synchronized Map<String, Object> pauseBusinessApplication(
            String pauseId, String instanceId, String expectedRevision, Instant expiresAt)
            throws Exception {
        guard();
        if (current.faulted()) throw new IllegalStateException("TARGET_BUSY");
        if (!"APPLIED".equals(configurationStatus))
            throw new IllegalStateException("NACOS_NOT_READY");
        if (preview().httpStatus() != 200) throw new IllegalStateException("BUSINESS_NOT_HEALTHY");
        return businessSettings.pauseApplication(pauseId, instanceId, expectedRevision, expiresAt);
    }

    synchronized Map<String, Object> resumeBusinessApplication(String pauseId, String instanceId)
            throws Exception {
        if (current.faulted()) throw new IllegalStateException("TARGET_BUSY");
        businessSettings.resumeApplication(pauseId, instanceId);
        return businessConfigurationView();
    }

    private Map<String, Object> businessConfigurationView() throws Exception {
        Map<String, Object> view = new LinkedHashMap<>(businessSettings.view());
        view.put("namespaceId", namespace == null || namespace.isBlank() ? "public" : namespace);
        view.put(
                "sourceInstanceId",
                "nacos-" + DemoBusinessSettings.digest("SHA-256", address).substring(0, 16));
        return view;
    }

    BusinessResult preview() {
        Entry entry = null;
        int status;
        String reason;
        String catalog = "";
        try {
            entry = SphU.entry(DemoConfiguration.RESOURCE);
            catalog = redis.catalog(current.redisPort());
            if (catalog == null) throw new IllegalStateException("CATALOG_MISSING");
            passed.increment();
            status = 200;
            reason = "ORDER_PREVIEW_READY";
        } catch (BlockException exception) {
            blocked.increment();
            status = 429;
            reason = "SENTINEL_BLOCKED";
        } catch (Exception exception) {
            errors.increment();
            status = 503;
            reason =
                    current.redisPort() == 6380
                            ? "REDIS_CONNECT_FAILED"
                            : "REDIS_DEPENDENCY_UNAVAILABLE";
        } finally {
            if (entry != null) entry.exit();
        }
        lastHttpStatus = status;
        lastReason = reason;
        lastObserved = Instant.now();
        DemoBusinessSettings.Values settings = businessSettings.applied();
        return new BusinessResult(
                status,
                reason,
                lastObserved,
                settings.catalogTitle(),
                settings.notice(),
                settings.discountPercent(),
                100,
                100 - settings.discountPercent(),
                status == 200 ? catalog : "",
                businessSettings.appliedRevision());
    }

    Map<String, Object> snapshot() {
        DemoConfiguration value = current;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetCode", DemoConfiguration.TARGET);
        result.put("scope", "ISOLATED_DEMO");
        result.put("incidentId", value.incidentId());
        result.put("scenarioCode", value.scenarioCode());
        result.put("status", value.faulted() ? "FAULT_ACTIVE" : "BASELINE");
        result.put("appliedRevision", value.revision());
        result.put("configurationStatus", configurationStatus);
        result.put("configurationSource", "NACOS");
        result.put("configurationAppliedAt", configurationAppliedAt);
        result.put("configuration", configurationProjection(value));
        result.put("previousConfiguration", previousConfiguration);
        result.put("redisPort", value.redisPort());
        result.put(
                "expiresAt",
                value.expiresAtEpoch() == 0 ? null : Instant.ofEpochSecond(value.expiresAtEpoch()));
        result.put("recoverySource", value.recoverySource());
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("httpStatus", lastHttpStatus);
        business.put("reasonCode", lastReason);
        business.put("observedAt", lastObserved);
        result.put("business", business);
        result.put(
                "sentinel",
                Map.of(
                        "resource",
                        DemoConfiguration.RESOURCE,
                        "qps",
                        value.qps(),
                        "passedTotal",
                        passed.count(),
                        "blockedTotal",
                        blocked.count()));
        return result;
    }

    private Map<String, Object> configurationProjection(DemoConfiguration value) {
        return Map.of(
                "redisPort",
                value.redisPort(),
                "sentinelQps",
                value.qps(),
                "sentinelResource",
                DemoConfiguration.RESOURCE,
                "revision",
                value.revision());
    }

    @PreDestroy
    void close() throws Exception {
        configEvents.shutdownNow();
        if (config != null) config.shutDown();
        if (naming != null) naming.shutDown();
    }

    /**
     * @author heyu
     */
    public record BusinessResult(
            int httpStatus,
            String reasonCode,
            Instant observedAt,
            String catalogTitle,
            String notice,
            int discountPercent,
            int basePrice,
            int quotedPrice,
            String catalog,
            String businessConfigurationRevision) {}
}
