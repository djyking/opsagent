package com.opsagent.demo;

import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 独立订单展示与报价配置；不包含端口、地址、密钥或故障参数。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class DemoBusinessSettings {
    private static final Logger LOG = LoggerFactory.getLogger(DemoBusinessSettings.class);
    static final String DATA_ID = "ops-demo-order-business.json";
    static final String GROUP = "OPSAGENT_DEMO";
    private final ObjectMapper json;
    private ConfigService config;
    private volatile Values applied = new Values("OpsAgent 演示订单", "来自独立 Redis 目录的实时订单预览", 0);
    private volatile String appliedRevision = "";
    private volatile String applicationStatus = "INITIALIZING";
    private final String instanceId = runtimeInstanceId();
    private volatile Instant appliedAt;
    private String pauseId = "";
    private Instant pauseStartedAt;
    private Instant pauseExpiresAt;
    private Instant pauseEndedAt;
    private String pauseRecoverySource = "";
    private String pauseBaseRevision = "";

    DemoBusinessSettings(ObjectMapper json) {
        this.json = json;
    }

    private static String runtimeInstanceId() {
        String configured = System.getenv("OPS_RUNTIME_INSTANCE_ID");
        if (configured != null
                && configured.matches(
                        "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            return configured;
        return java.util.UUID.randomUUID().toString();
    }

    void initialize(ConfigService service) throws Exception {
        config = service;
        String content = config.getConfig(DATA_ID, GROUP, 1500);
        if (content == null || content.isBlank()) {
            String initial =
                    envelope("00000000-0000-0000-0000-000000000001", json.valueToTree(applied));
            if (!config.publishConfigCas(DATA_ID, GROUP, initial, digest("MD5", ""), "json")) {
                applicationStatus = "NACOS_CONFIRMATION_PENDING";
                return;
            }
            confirm(initial);
        } else accept(content);
    }

    synchronized void accept(String raw) throws Exception {
        JsonNode envelope = parseEnvelope(raw);
        if (applicationPaused() && Instant.now().isBefore(pauseExpiresAt)) {
            applicationStatus = "DEFERRED_APPLICATION_PAUSE";
            return;
        }
        if (applicationPaused()) endPause("TTL_GUARD");
        applied = values(envelope.path("configuration"));
        appliedRevision = digest("SHA-256", raw);
        applicationStatus = "APPLIED";
        appliedAt = Instant.now();
    }

    void deferred() {
        applicationStatus = "DEFERRED_DURING_INCIDENT";
    }

    void invalid() {
        applicationStatus = "INVALID_OR_UNAVAILABLE_CONFIGURATION";
    }

    synchronized Map<String, Object> view() throws Exception {
        if (config == null) throw new IllegalStateException("NACOS_NOT_READY");
        String raw = config.getConfig(DATA_ID, GROUP, 1500);
        JsonNode document = parseEnvelope(raw);
        String observedRevision = digest("SHA-256", raw);
        Map<String, Object> view =
                new LinkedHashMap<String, Object>(
                        Map.<String, Object>of(
                                "content",
                                json.valueToTree(values(document.path("configuration"))),
                                "revision",
                                observedRevision,
                                "appliedRevision",
                                appliedRevision,
                                "applicationStatus",
                                observedRevision.equals(appliedRevision)
                                        ? "APPLIED"
                                        : applicationStatus,
                                "nacosStatus",
                                "AVAILABLE",
                                "publicationId",
                                document.path("publicationId").asText(),
                                "observedAt",
                                Instant.now().toString()));
        view.put("instanceId", instanceId);
        view.put("appliedAt", appliedAt == null ? "" : appliedAt.toString());
        view.put("appliedContent", json.valueToTree(applied));
        view.put("targetCode", "ops-demo-order-service");
        view.put("applicationPause", pauseView());
        return view;
    }

    synchronized Map<String, Object> pauseApplication(
            String id, String expectedInstance, String expectedRevision, Instant expiresAt)
            throws Exception {
        if (id == null || !id.matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("INVALID_PAUSE_ID");
        if (!instanceId.equals(expectedInstance))
            throw new IllegalStateException("INSTANCE_MISMATCH");
        if (id.equals(pauseId)) {
            if (!pauseExpiresAt.equals(expiresAt) || !pauseBaseRevision.equals(expectedRevision))
                throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
            return pauseView();
        }
        Instant now = Instant.now();
        if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(120)))
            throw new IllegalArgumentException("INVALID_PAUSE_TTL");
        if (applicationPaused()) throw new IllegalStateException("TARGET_BUSY");
        Map<String, Object> actual = view();
        if (!appliedRevision.equals(expectedRevision)
                || !actual.get("revision").equals(expectedRevision)
                || !"APPLIED".equals(actual.get("applicationStatus")))
            throw new IllegalStateException("REVISION_CONFLICT");
        pauseId = id;
        pauseStartedAt = now;
        pauseExpiresAt = expiresAt;
        pauseEndedAt = null;
        pauseRecoverySource = "";
        pauseBaseRevision = expectedRevision;
        LOG.info(
                "BUSINESS_APPLICATION_PAUSE_STARTED pauseId={} instanceId={} expiresAt={}"
                        + " revision={}",
                pauseId,
                instanceId,
                pauseExpiresAt,
                appliedRevision);
        return pauseView();
    }

    synchronized Map<String, Object> resumeApplication(String id, String expectedInstance)
            throws Exception {
        if (!instanceId.equals(expectedInstance))
            throw new IllegalStateException("INSTANCE_MISMATCH");
        if (!pauseId.equals(id) || pauseId.isBlank())
            throw new IllegalStateException("PAUSE_ID_MISMATCH");
        if (applicationPaused()) recoverPause("MANUAL");
        return view();
    }

    synchronized void recoverExpiredPause() throws Exception {
        if (applicationPaused() && !Instant.now().isBefore(pauseExpiresAt))
            recoverPause("TTL_GUARD");
    }

    private void recoverPause(String source) throws Exception {
        if (config == null) throw new IllegalStateException("NACOS_NOT_READY");
        String raw = config.getConfig(DATA_ID, GROUP, 1000);
        parseEnvelope(raw);
        endPause(source);
        accept(raw);
    }

    private void endPause(String source) {
        pauseEndedAt = Instant.now();
        pauseRecoverySource = source;
        LOG.info(
                "BUSINESS_APPLICATION_PAUSE_ENDED pauseId={} instanceId={} source={} endedAt={}",
                pauseId,
                instanceId,
                source,
                pauseEndedAt);
    }

    synchronized boolean applicationPaused() {
        return !pauseId.isBlank() && pauseEndedAt == null;
    }

    private Map<String, Object> pauseView() {
        return Map.of(
                "pauseId",
                pauseId,
                "active",
                applicationPaused(),
                "startedAt",
                pauseStartedAt == null ? "" : pauseStartedAt.toString(),
                "expiresAt",
                pauseExpiresAt == null ? "" : pauseExpiresAt.toString(),
                "endedAt",
                pauseEndedAt == null ? "" : pauseEndedAt.toString(),
                "recoverySource",
                pauseRecoverySource,
                "scope",
                "ISOLATED_ORDER_APPLICATION_ONLY");
    }

    synchronized Map<String, Object> publish(JsonNode value, String expected, String requestId)
            throws Exception {
        if (config == null) throw new IllegalStateException("NACOS_NOT_READY");
        if (value == null
                || !value.isObject()
                || value.size() != 3
                || !value.has("catalogTitle")
                || !value.has("notice")
                || !value.has("discountPercent"))
            throw new IllegalArgumentException("UNMANAGED_CONFIG_FIELD");
        String before = config.getConfig(DATA_ID, GROUP, 1500);
        JsonNode document = parseEnvelope(before);
        ObjectNode next = document.deepCopy();
        next.put("publicationId", requestId);
        ObjectNode merged = next.withObject("configuration");
        JsonNode normalized = json.valueToTree(values(value));
        normalized.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        String desired = json.writeValueAsString(next);
        if (document.path("publicationId").asText().equals(requestId)) {
            if (!json.valueToTree(values(document.path("configuration"))).equals(normalized))
                throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
            accept(before);
            return view();
        }
        if (!digest("SHA-256", before).equals(expected))
            throw new IllegalStateException("REVISION_CONFLICT");
        if (!config.publishConfigCas(DATA_ID, GROUP, desired, digest("MD5", before), "json")) {
            throw new IllegalStateException("NACOS_CAS_CONFLICT");
        }
        applicationStatus = "NACOS_CONFIRMATION_PENDING";
        confirm(desired);
        return view();
    }

    private void confirm(String desired) throws Exception {
        long start = System.nanoTime();
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3)) {
            String observed = config.getConfig(DATA_ID, GROUP, 400);
            if (desired.equals(observed)) {
                accept(observed);
                return;
            }
            Thread.sleep(80);
        }
        // An accepted publication is not proof that the service has observed and applied it.
        throw new IllegalStateException("CONFIG_CONFIRMATION_PENDING");
    }

    private String envelope(String publicationId, JsonNode value) throws Exception {
        if (publicationId == null || !publicationId.matches("[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("INVALID_PUBLICATION_ID");
        }
        Values normalized = values(value);
        return json.writeValueAsString(
                Map.<String, Object>of(
                        "publicationId", publicationId, "configuration", normalized));
    }

    private JsonNode parseEnvelope(String raw) throws Exception {
        if (raw == null || raw.length() > 8192)
            throw new IllegalArgumentException("INVALID_BUSINESS_CONFIG");
        JsonNode document = json.readTree(raw);
        if (!document.isObject()
                || !document.path("publicationId").asText().matches("[a-f0-9-]{36}")
                || !document.has("configuration"))
            throw new IllegalArgumentException("INVALID_BUSINESS_CONFIG");
        values(document.path("configuration"));
        return document;
    }

    static Values values(JsonNode value) {
        if (value == null
                || !value.isObject()
                || !value.path("catalogTitle").isTextual()
                || !value.path("notice").isTextual()
                || !value.path("discountPercent").isIntegralNumber()) {
            throw new IllegalArgumentException("INVALID_BUSINESS_CONFIG");
        }
        String title = value.path("catalogTitle").asText();
        String notice = value.path("notice").asText();
        long discount = value.path("discountPercent").asLong(-1);
        if (title.isBlank()
                || title.length() > 60
                || notice.length() > 160
                || title.chars().anyMatch(Character::isISOControl)
                || notice.chars().anyMatch(Character::isISOControl)
                || discount < 0
                || discount > 30
                || !value.path("discountPercent").canConvertToInt()) {
            throw new IllegalArgumentException("INVALID_BUSINESS_CONFIG");
        }
        return new Values(title, notice, (int) discount);
    }

    Values applied() {
        return applied;
    }

    String appliedRevision() {
        return appliedRevision;
    }

    static String digest(String algorithm, String content) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance(algorithm)
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HASH_UNAVAILABLE");
        }
    }

    /**
     * @author heyu
     */
    record Values(String catalogTitle, String notice, int discountPercent) {}
}
