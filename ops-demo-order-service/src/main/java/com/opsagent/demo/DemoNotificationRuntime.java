package com.opsagent.demo;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.naming.NamingService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 同一 Demo JVM 内的第二个真实业务目标，独立 Nacos 状态保留 TTL 与恢复来源。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class DemoNotificationRuntime {
    static final String DATA_ID = "ops-demo-notification-runtime.json";
    private static final String GROUP = "OPSAGENT_DEMO";
    private final DemoNotificationBroker broker;
    private final ObjectMapper json;
    private ConfigService config;
    private volatile DemoNotificationConfiguration current =
            DemoNotificationConfiguration.baseline("", "BASELINE");
    private volatile String configurationStatus = "INITIALIZING";
    private volatile Instant appliedAt = Instant.now();
    private Map<String, Object> previousConfiguration = Map.of();
    private boolean configurationObserved;
    private boolean baselineNeedsPublish;
    private volatile Map<String, Object> lastBusiness =
            Map.of("httpStatus", 0, "reasonCode", "NOT_PROBED");

    DemoNotificationRuntime(DemoNotificationBroker broker, ObjectMapper json) {
        this.broker = broker;
        this.json = json;
    }

    synchronized void initialize(ConfigService service, NamingService naming, Executor executor)
            throws Exception {
        config = service;
        String content = config.getConfig(DATA_ID, GROUP, 2000);
        if (content == null || content.isBlank()) publish(current);
        else accept(content);
        config.addListener(
                DATA_ID,
                GROUP,
                new Listener() {
                    @Override
                    public Executor getExecutor() {
                        return executor;
                    }

                    @Override
                    public void receiveConfigInfo(String ignored) {
                        try {
                            accept(config.getConfig(DATA_ID, GROUP, 2000));
                        } catch (Exception failure) {
                            configurationStatus = "INVALID_CONFIGURATION";
                        }
                    }
                });
        naming.registerInstance(
                DemoNotificationConfiguration.TARGET,
                InetAddress.getLocalHost().getHostAddress(),
                8110);
        maintain();
    }

    synchronized Map<String, Object> inject(String incident, String scenario, Instant expiry)
            throws Exception {
        guard();
        if (current.faulted()) {
            if (current.incidentId().equals(incident) && current.scenarioCode().equals(scenario))
                return snapshot();
            throw new IllegalStateException("TARGET_BUSY");
        }
        if (!"APPLIED".equals(configurationStatus))
            throw new IllegalStateException("NACOS_NOT_READY");
        publish(DemoNotificationConfiguration.fault(incident, scenario, expiry));
        return snapshot();
    }

    synchronized Map<String, Object> restore(
            String incident, String action, String revision, String source) throws Exception {
        if (!Set.of("AGENT_TOOL", "MANUAL").contains(source))
            throw new IllegalArgumentException("INVALID_ACTOR_SOURCE");
        if (!"RESTORE_QUEUE_CONSUMER".equals(action))
            throw new IllegalArgumentException("ACTION_SCENARIO_MISMATCH");
        if (!current.incidentId().equals(incident))
            throw new IllegalArgumentException("INCIDENT_MISMATCH");
        if (!current.faulted()) return snapshot();
        if (!current.revision().equals(revision))
            throw new IllegalStateException("REVISION_CONFLICT");
        publish(DemoNotificationConfiguration.baseline(incident, source));
        return snapshot();
    }

    synchronized void accept(String content) throws Exception {
        if (content == null || content.length() > 4096)
            throw new IllegalArgumentException("INVALID_CONFIG_SIZE");
        var value = json.readValue(content, DemoNotificationConfiguration.class);
        if (!value.valid()) throw new IllegalArgumentException("INVALID_DEMO_CONFIG");
        if (value.faulted() && value.expiresAtEpoch() <= Instant.now().getEpochSecond()) {
            apply(DemoNotificationConfiguration.baseline(value.incidentId(), "TTL_GUARD"));
            baselineNeedsPublish = true;
        } else apply(value);
        configurationStatus = "APPLIED";
    }

    private void publish(DemoNotificationConfiguration value) throws Exception {
        if (config == null) throw new IllegalStateException("NACOS_NOT_READY");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        if (!config.publishConfig(DATA_ID, GROUP, json.writeValueAsString(value), "json"))
            throw new IllegalStateException("CONFIG_PUBLISH_REJECTED");
        configurationStatus = "NACOS_CONFIRMATION_PENDING";
        while (System.nanoTime() < deadline) {
            try {
                String raw = config.getConfig(DATA_ID, GROUP, 500);
                if (raw != null && raw.length() <= 4096) {
                    var observed = json.readValue(raw, DemoNotificationConfiguration.class);
                    if (observed.valid() && observed.revision().equals(value.revision())) {
                        accept(raw);
                        if (!current.revision().equals(value.revision()))
                            throw new IllegalStateException("CONFIG_NOT_APPLIED");
                        return;
                    }
                }
            } catch (com.alibaba.nacos.api.exception.NacosException ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            }
        }
        throw new IllegalStateException("CONFIG_CONFIRMATION_PENDING");
    }

    private void apply(DemoNotificationConfiguration value) {
        if (!configurationObserved || !current.revision().equals(value.revision())) {
            previousConfiguration = configurationObserved ? projection(current) : Map.of();
            appliedAt = Instant.now();
        }
        configurationObserved = true;
        current = value;
        try {
            broker.maintain(value.consumerEnabled());
        } catch (Exception ignored) {
            /* Business probes must separately confirm broker recovery. */
        }
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 10000)
    synchronized void guard() {
        if (current.faulted() && current.expiresAtEpoch() <= Instant.now().getEpochSecond()) {
            apply(DemoNotificationConfiguration.baseline(current.incidentId(), "TTL_GUARD"));
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

    @Scheduled(fixedDelay = 2000, initialDelay = 15000)
    synchronized void maintain() {
        if (config == null || "INITIALIZING".equals(configurationStatus)) return;
        try {
            broker.maintain(current.consumerEnabled());
            broker.publish(UUID.randomUUID().toString());
        } catch (Exception ignored) {
            /* Failed I/O is exposed by the real business probe. */
        }
    }

    Map<String, Object> preview() {
        var delivery = broker.probe();
        Map<String, Object> queue = queueSnapshot();
        boolean drained = ((Number) queue.getOrDefault("messagesReady", -1)).longValue() == 0;
        boolean consumerRunning = ((Number) queue.getOrDefault("consumerCount", 0)).intValue() == 1;
        int status =
                delivery.delivered() && drained && consumerRunning && current.consumerEnabled()
                        ? 200
                        : 503;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("httpStatus", status);
        result.put(
                "reasonCode",
                status == 200
                        ? "NOTIFICATION_DELIVERED"
                        : delivery.delivered()
                                ? "NOTIFICATION_BACKLOG_REMAINS"
                                : delivery.reasonCode());
        result.put("observedAt", Instant.now());
        result.put("messageId", delivery.messageId());
        result.put("deliveredAt", delivery.deliveredAt());
        result.put("receiptStore", "ISOLATED_REDIS_READ_BACK");
        result.put("queueDrained", drained);
        result.put("queue", queue);
        lastBusiness = result;
        return result;
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetCode", DemoNotificationConfiguration.TARGET);
        result.put("scope", "ISOLATED_DEMO");
        result.put("incidentId", current.incidentId());
        result.put("scenarioCode", current.scenarioCode());
        result.put("status", current.faulted() ? "FAULT_ACTIVE" : "BASELINE");
        result.put("appliedRevision", current.revision());
        result.put("configurationStatus", configurationStatus);
        result.put("configurationSource", "NACOS");
        result.put("configurationAppliedAt", appliedAt);
        result.put("configuration", projection(current));
        result.put("previousConfiguration", previousConfiguration);
        result.put("consumerEnabled", current.consumerEnabled());
        result.put(
                "expiresAt",
                current.expiresAtEpoch() == 0
                        ? null
                        : Instant.ofEpochSecond(current.expiresAtEpoch()));
        result.put("recoverySource", current.recoverySource());
        result.put("queue", queueSnapshot());
        result.put("business", lastBusiness);
        return result;
    }

    private Map<String, Object> queueSnapshot() {
        try {
            return broker.snapshot();
        } catch (Exception ignored) {
            return Map.of(
                    "queue",
                    DemoNotificationBroker.QUEUE,
                    "vhost",
                    DemoNotificationBroker.VHOST,
                    "messagesReady",
                    -1,
                    "consumerCount",
                    0,
                    "status",
                    "UNAVAILABLE",
                    "observedAt",
                    Instant.now());
        }
    }

    private Map<String, Object> projection(DemoNotificationConfiguration value) {
        return Map.of(
                "consumerEnabled",
                value.consumerEnabled(),
                "queue",
                DemoNotificationBroker.QUEUE,
                "vhost",
                DemoNotificationBroker.VHOST,
                "revision",
                value.revision());
    }
}
