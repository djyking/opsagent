package com.opsagent.demo;

import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 独立订单展示与报价配置；不包含端口、地址、密钥或故障参数。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class DemoBusinessSettings {
    static final String DATA_ID = "ops-demo-order-business.json";
    static final String GROUP = "OPSAGENT_DEMO";
    private final ObjectMapper json;
    private ConfigService config;
    private volatile Values applied = new Values("OpsAgent 演示订单", "来自独立 Redis 目录的实时订单预览", 0);
    private volatile String appliedRevision = "";
    private volatile String applicationStatus = "INITIALIZING";

    DemoBusinessSettings(ObjectMapper json) {
        this.json = json;
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

    void accept(String raw) throws Exception {
        JsonNode envelope = parseEnvelope(raw);
        applied = values(envelope.path("configuration"));
        appliedRevision = digest("SHA-256", raw);
        applicationStatus = "APPLIED";
    }

    void deferred() {
        applicationStatus = "DEFERRED_DURING_INCIDENT";
    }

    void invalid() {
        applicationStatus = "INVALID_OR_UNAVAILABLE_CONFIGURATION";
    }

    Map<String, Object> view() throws Exception {
        if (config == null) throw new IllegalStateException("NACOS_NOT_READY");
        String raw = config.getConfig(DATA_ID, GROUP, 1500);
        JsonNode document = parseEnvelope(raw);
        String observedRevision = digest("SHA-256", raw);
        return Map.of(
                "content",
                document.path("configuration"),
                "revision",
                observedRevision,
                "appliedRevision",
                appliedRevision,
                "applicationStatus",
                observedRevision.equals(appliedRevision) ? "APPLIED" : applicationStatus,
                "nacosStatus",
                "AVAILABLE",
                "publicationId",
                document.path("publicationId").asText(),
                "observedAt",
                Instant.now().toString());
    }

    Map<String, Object> publish(JsonNode value, String expected, String requestId)
            throws Exception {
        if (config == null) throw new IllegalStateException("NACOS_NOT_READY");
        String desired = envelope(requestId, value);
        String before = config.getConfig(DATA_ID, GROUP, 1500);
        JsonNode document = parseEnvelope(before);
        if (document.path("publicationId").asText().equals(requestId)) {
            if (!document.path("configuration").equals(value))
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
                Map.of("publicationId", publicationId, "configuration", normalized));
    }

    private JsonNode parseEnvelope(String raw) throws Exception {
        if (raw == null || raw.length() > 8192)
            throw new IllegalArgumentException("INVALID_BUSINESS_CONFIG");
        JsonNode document = json.readTree(raw);
        if (!document.isObject()
                || document.size() != 2
                || !document.path("publicationId").asText().matches("[a-f0-9-]{36}")
                || !document.has("configuration"))
            throw new IllegalArgumentException("INVALID_BUSINESS_CONFIG");
        values(document.path("configuration"));
        return document;
    }

    static Values values(JsonNode value) {
        if (value == null
                || !value.isObject()
                || value.size() != 3
                || !value.path("catalogTitle").isTextual()
                || !value.path("notice").isTextual()
                || !value.path("discountPercent").isIntegralNumber()) {
            throw new IllegalArgumentException("INVALID_BUSINESS_CONFIG");
        }
        value.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!Set.of("catalogTitle", "notice", "discountPercent")
                                    .contains(field)) {
                                throw new IllegalArgumentException("UNMANAGED_CONFIG_FIELD");
                            }
                        });
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
