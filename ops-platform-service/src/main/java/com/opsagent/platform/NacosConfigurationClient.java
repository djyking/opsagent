package com.opsagent.platform;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * Fixed Nacos 3 adapter. Raw content stays inside the service boundary.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class NacosConfigurationClient {
    static final class SourceFailure extends IllegalStateException {
        private final String code;

        SourceFailure(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    record Entry(String dataId, String group, String type, String modifiedAt) {}

    record Content(String value, String type, String revision, boolean exists) {}

    record Version(long id, String actor, String modifiedAt, String operation) {}

    private final ObjectMapper json;
    private final HttpClient http;

    @Value("${spring.cloud.nacos.discovery.enabled:false}")
    private boolean enabled;

    @Value("${ops.operations.nacos-url:http://${NACOS_SERVER_ADDR:localhost:8848}/nacos}")
    private String url;

    @Value("${ops.operations.nacos-username:}")
    private String username = "";

    @Value("${ops.operations.nacos-password:}")
    private String password = "";

    @Value("${ops.operations.nacos-identity-key:}")
    private String identityKey = "";

    @Value("${ops.operations.nacos-identity-value:}")
    private String identityValue = "";

    @Value("${ops.operations.nacos-namespace:}")
    private String namespace = "";

    @Value("${ops.configuration.environment:${spring.profiles.active:default}}")
    private String environment = "default";

    private volatile ConfigService sdk;

    @Autowired
    NacosConfigurationClient(ObjectMapper json) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    NacosConfigurationClient(ObjectMapper json, HttpClient http) {
        this.json = json;
        this.http = http;
    }

    boolean enabled() {
        return enabled;
    }

    String namespace() {
        return namespace == null || namespace.isBlank() ? "public" : namespace;
    }

    String sourceInstanceId() {
        return "nacos-"
                + sha256(url == null ? "unconfigured" : URI.create(url).getAuthority())
                        .substring(0, 16);
    }

    String environment() {
        return environment;
    }

    List<Entry> catalog() throws Exception {
        List<Entry> entries = new ArrayList<>();
        String access = access();
        for (int page = 1; page <= 5; page++) {
            JsonNode data =
                    get(
                            "/v3/admin/cs/config/list?pageNo="
                                    + page
                                    + "&pageSize=100&namespaceId="
                                    + encode(namespace())
                                    + access);
            if (!data.path("pageItems").isArray())
                throw new IllegalStateException("Nacos catalog unavailable");
            for (JsonNode item : data.path("pageItems")) {
                String id = item.path("dataId").asText();
                String group = item.path("groupName").asText();
                if (id.startsWith("ops") && safeName(id) && safeName(group))
                    entries.add(
                            new Entry(
                                    id,
                                    group,
                                    format(id, item.path("type").asText()),
                                    safeText(item.path("modifyTime").asText())));
            }
            if (data.path("pageItems").size() < 100) break;
        }
        return entries;
    }

    Content content(String dataId, String group) throws Exception {
        JsonNode data = get("/v3/admin/cs/config?" + query(dataId, group) + access());
        verifyIdentity(data, dataId, group);
        if (!data.path("content").isTextual()) throw new SourceFailure("INVALID_CONTENT");
        String raw = data.path("content").asText();
        return new Content(raw, format(dataId, data.path("type").asText()), sha256(raw), true);
    }

    List<Version> history(String dataId, String group) throws Exception {
        JsonNode data =
                get(
                        "/v3/admin/cs/history/list?"
                                + query(dataId, group)
                                + "&pageNo=1&pageSize=20"
                                + access());
        if (!data.path("pageItems").isArray())
            throw new IllegalStateException("Nacos history unavailable");
        List<Version> result = new ArrayList<>();
        for (JsonNode item : data.path("pageItems")) {
            long id = item.path("id").asLong(item.path("nid").asLong());
            if (id > 0)
                result.add(
                        new Version(
                                id,
                                safeText(item.path("srcUser").asText("Nacos")),
                                safeText(
                                        item.path("modifyTime")
                                                .asText(item.path("lastModifiedTime").asText())),
                                safeText(item.path("opType").asText("UPDATE"))));
        }
        return result;
    }

    Content version(String dataId, String group, long id) throws Exception {
        JsonNode data =
                get("/v3/admin/cs/history?" + query(dataId, group) + "&nid=" + id + access());
        verifyIdentity(data, dataId, group);
        if (!data.path("content").isTextual()
                || !dataId.equals(data.path("dataId").asText())
                || !group.equals(data.path("groupName").asText(data.path("group").asText())))
            throw new SourceFailure("INVALID_CONTENT");
        String raw = data.path("content").asText();
        return new Content(raw, format(dataId, data.path("type").asText()), sha256(raw), true);
    }

    // SDK CAS was verified against the deployed Nacos 3 server; admin POST is not assumed to offer
    // CAS.
    boolean compareAndPublish(String dataId, String group, String before, String desired)
            throws Exception {
        return sdk().publishConfigCas(dataId, group, desired, digest("MD5", before), "json");
    }

    Content ruleContent(String dataId, String group) throws Exception {
        String raw = sdk().getConfig(dataId, group, 3000);
        return new Content(
                raw == null ? "" : raw, "json", sha256(raw == null ? "" : raw), raw != null);
    }

    private synchronized ConfigService sdk() throws Exception {
        if (!enabled) throw new IllegalStateException("Nacos disabled");
        if (sdk == null) {
            Properties properties = new Properties();
            URI endpoint = URI.create(url);
            properties.setProperty(
                    "serverAddr",
                    endpoint.getHost()
                            + ":"
                            + (endpoint.getPort() < 0 ? 8848 : endpoint.getPort()));
            properties.setProperty("namespace", "public".equals(namespace()) ? "" : namespace());
            if (!username.isBlank()) {
                properties.setProperty("username", username);
                properties.setProperty("password", password);
            }
            sdk = NacosFactory.createConfigService(properties);
        }
        return sdk;
    }

    @PreDestroy
    void shutdown() {
        if (sdk != null)
            try {
                sdk.shutDown();
            } catch (Exception ignored) {
            }
    }

    private String query(String id, String group) {
        if (!safeName(id) || !id.startsWith("ops") || !safeName(group))
            throw new IllegalArgumentException("Invalid configuration key");
        return "dataId="
                + encode(id)
                + "&groupName="
                + encode(group)
                + "&namespaceId="
                + encode(namespace());
    }

    private String access() throws Exception {
        if (!enabled) throw new SourceFailure("UNSUPPORTED");
        if (hasIdentity() || username.isBlank()) return "";
        JsonNode login =
                send(
                        HttpRequest.newBuilder(URI.create(url + "/v3/auth/user/login"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "username="
                                                        + encode(username)
                                                        + "&password="
                                                        + encode(password))));
        String token =
                (login.has("accessToken") ? login : login.path("data"))
                        .path("accessToken")
                        .asText();
        if (token.isBlank()) throw new SourceFailure("FORBIDDEN");
        return "&accessToken=" + encode(token);
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + path)).GET();
        if (hasIdentity()) request.header(identityKey, identityValue);
        JsonNode response = send(request);
        int code = response.path("code").asInt(-1);
        if (code != 0)
            throw new SourceFailure(
                    code == 403 || code == 401
                            ? "FORBIDDEN"
                            : code == 404 || code == 20004 ? "NOT_FOUND" : "UPSTREAM_UNAVAILABLE");
        return response.path("data");
    }

    private JsonNode send(HttpRequest.Builder request) throws Exception {
        var response =
                http.send(
                        request.timeout(Duration.ofSeconds(4)).build(),
                        HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new SourceFailure(
                    response.statusCode() == 403 || response.statusCode() == 401
                            ? "FORBIDDEN"
                            : response.statusCode() == 404 ? "NOT_FOUND" : "UPSTREAM_UNAVAILABLE");
        if (response.body().length() > 2_000_000) throw new SourceFailure("INVALID_CONTENT");
        try {
            return json.readTree(response.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new SourceFailure("INVALID_CONTENT");
        }
    }

    private void verifyIdentity(JsonNode data, String dataId, String group) {
        if (data.isMissingNode() || data.isNull()) throw new SourceFailure("NOT_FOUND");
        if (!dataId.equals(data.path("dataId").asText())
                || !group.equals(data.path("groupName").asText(data.path("group").asText()))
                || data.hasNonNull("namespaceId")
                        && !namespace()
                                .equals(
                                        data.path("namespaceId").asText().isBlank()
                                                ? "public"
                                                : data.path("namespaceId").asText()))
            throw new SourceFailure("INVALID_CONTENT");
    }

    private boolean hasIdentity() {
        return !identityKey.isBlank() && !identityValue.isBlank();
    }

    static boolean safeName(String text) {
        return text != null && text.matches("[a-zA-Z0-9_.:@-]{1,128}");
    }

    private static String safeText(String text) {
        return text.length() <= 128 && !text.contains("\n") ? text : "";
    }

    private static String format(String id, String type) {
        return type.isBlank() ? id.substring(id.lastIndexOf('.') + 1) : type;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String sha256(String value) {
        return digest("SHA-256", value);
    }

    private static String digest(String algorithm, String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance(algorithm)
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Digest unavailable");
        }
    }
}
