package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 从固定集成地址读取 Nacos 元数据和 RAG Sentinel 已生效规则，只投影安全字段。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class OperationsIntegrationClient {
    private final ObjectMapper json;
    private final HttpClient http;

    @Value("${spring.cloud.nacos.discovery.enabled:false}")
    private boolean nacosEnabled;

    @Value("${ops.operations.nacos-url:http://${NACOS_SERVER_ADDR:localhost:8848}/nacos}")
    private String nacosUrl;

    @Value("${ops.operations.nacos-username:}")
    private String nacosUsername;

    @Value("${ops.operations.nacos-password:}")
    private String nacosPassword;

    @Value("${ops.operations.nacos-identity-key:}")
    private String nacosIdentityKey;

    @Value("${ops.operations.nacos-identity-value:}")
    private String nacosIdentityValue;

    @Value("${ops.operations.nacos-namespace:}")
    private String namespace;

    @Value("${ops.operations.rag-url:http://localhost:8104}")
    private String ragUrl;

    @Autowired
    OperationsIntegrationClient(ObjectMapper json) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    OperationsIntegrationClient(ObjectMapper json, HttpClient http) {
        this.json = json;
        this.http = http;
    }

    OperationsDtos.Nacos nacos() {
        if (!nacosEnabled) return new OperationsDtos.Nacos("DISABLED", "当前未启用 Nacos 服务发现。",
                null, null, null, List.of(), List.of());
        List<OperationsDtos.NacosService> services = new ArrayList<>();
        List<OperationsDtos.Configuration> configurations = new ArrayList<>();
        String access = "";
        String namespaceId = namespace == null || namespace.isBlank() ? "public" : namespace;
        boolean discoveryAvailable = false;
        boolean configAvailable = false;
        try {
            if (!hasServerIdentity() && !nacosUsername.isBlank()) {
                String form = "username=" + encode(nacosUsername) + "&password=" + encode(nacosPassword);
                JsonNode login = send(HttpRequest.newBuilder(URI.create(nacosUrl + "/v3/auth/user/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)));
                String token = (login.has("accessToken") ? login : login.path("data")).path("accessToken").asText();
                if (token.isBlank()) throw new IllegalStateException("Nacos authentication unavailable");
                access = "&accessToken=" + encode(token);
            }
            JsonNode catalog = nacosGet("/v3/admin/ns/service/list?pageNo=1&pageSize=100&namespaceId="
                    + encode(namespaceId) + access);
            JsonNode items = pageItems(catalog);
            for (JsonNode item : items) {
                String name = item.path("name").asText();
                if (!safeName(name) || !name.startsWith("ops-")) continue;
                services.add(new OperationsDtos.NacosService(name, integer(item, "ipCount"),
                        integer(item, "healthyInstanceCount")));
            }
            discoveryAvailable = true;
        } catch (Exception ignored) {
            // Configuration values and HTTP exception messages must never become UI/RAG context.
        }
        try {
            JsonNode config = nacosGet("/v3/admin/cs/config/list?pageNo=1&pageSize=100&namespaceId="
                    + encode(namespaceId) + access);
            JsonNode items = pageItems(config);
            for (JsonNode item : items) {
                String dataId = item.path("dataId").asText();
                String group = item.path("groupName").asText();
                if (safeName(dataId) && safeName(group) && dataId.startsWith("ops")) {
                    configurations.add(new OperationsDtos.Configuration(dataId, group,
                            item.path("modifyTime").isIntegralNumber() && item.path("modifyTime").asLong() > 0
                                    ? item.path("modifyTime").asText() : ""));
                }
            }
            configAvailable = true;
        } catch (Exception ignored) {
            // Nacos response content is intentionally not returned or logged.
        }
        Integer healthy = discoveryAvailable && services.stream().allMatch(item -> item.healthyInstanceCount() != null)
                ? services.stream().mapToInt(OperationsDtos.NacosService::healthyInstanceCount).sum() : null;
        String state = discoveryAvailable && configAvailable ? "AVAILABLE"
                : discoveryAvailable || configAvailable ? "PARTIAL" : "UNAVAILABLE";
        String message = "来自Nacos 3管理接口；仅展示OpsAgent注册实例和配置元信息，最多100条，不查询配置正文。";
        if (!discoveryAvailable) message += " 注册元数据暂不可用。";
        if (!configAvailable) message += " 配置元数据暂不可用，请检查连接或管理接口访问身份。";
        return new OperationsDtos.Nacos(state, message, discoveryAvailable ? services.size() : null, healthy,
                configAvailable ? configurations.size() : null, services, configurations);
    }

    private boolean hasServerIdentity() {
        return nacosIdentityKey != null && !nacosIdentityKey.isBlank()
                && nacosIdentityValue != null && !nacosIdentityValue.isBlank();
    }

    private JsonNode nacosGet(String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(nacosUrl + path)).GET();
        if (hasServerIdentity()) request.header(nacosIdentityKey, nacosIdentityValue);
        return send(request);
    }

    private JsonNode pageItems(JsonNode response) {
        JsonNode items = response.path("data").path("pageItems");
        if (response.path("code").asInt(-1) != 0 || !items.isArray()) {
            throw new IllegalStateException("Nacos metadata unavailable");
        }
        return items;
    }

    OperationsDtos.Sentinel sentinel() {
        String authorization = null;
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            authorization = servlet.getRequest().getHeader("Authorization");
        }
        try {
            JsonNode response = get(ragUrl + "/api/rag/runtime/sentinel", authorization);
            if (response.path("code").asInt(-1) != 0) throw new IllegalStateException("Runtime unavailable");
            JsonNode body = response.path("data");
            List<OperationsDtos.FlowRule> rules = new ArrayList<>();
            for (JsonNode item : body.path("rules")) {
                String resource = item.path("resource").asText();
                if (!"ops-rag-ask".equals(resource)) continue;
                Double count = number(item, "count");
                if (count != null) rules.add(new OperationsDtos.FlowRule(resource,
                        "THREAD".equals(item.path("grade").asText()) ? "THREAD" : "QPS", count,
                        safeText(item.path("controlBehavior").asText())));
            }
            return new OperationsDtos.Sentinel("AVAILABLE",
                    rules.isEmpty() ? "RAG运行时未加载该入口限流规则；计数从当前进程启动后累计。"
                            : "来自RAG运行时已生效规则；通过/拦截计数从当前进程启动后累计，不含AI预算拦截。",
                    "Sentinel runtime FlowRuleManager", rules,
                    number(body, "passedTotal"), number(body, "blockedTotal"),
                    safeText(body.path("observedAt").asText()));
        } catch (Exception ignored) {
            return new OperationsDtos.Sentinel("UNAVAILABLE", "无法读取RAG运行时限流规则或计数，不能据此判断限流状态。",
                    "Sentinel runtime FlowRuleManager", List.of(), null, null, null);
        }
    }

    private JsonNode get(String url, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (authorization != null && authorization.startsWith("Bearer ")) {
            request.header("Authorization", authorization);
        }
        return send(request);
    }

    private JsonNode send(HttpRequest.Builder builder) throws Exception {
        HttpResponse<String> response = http.send(builder.timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Integration unavailable");
        if (response.body().length() > 2_000_000) throw new IllegalStateException("Integration response too large");
        return json.readTree(response.body());
    }

    private Integer integer(JsonNode node, String field) {
        return node.path(field).isIntegralNumber() ? node.path(field).intValue() : null;
    }

    private Double number(JsonNode node, String field) {
        return node.path(field).isNumber() && Double.isFinite(node.path(field).doubleValue())
                ? node.path(field).doubleValue() : null;
    }

    private boolean safeName(String text) {
        return text.matches("[a-zA-Z0-9_.:@-]{1,128}");
    }

    private String safeText(String text) {
        return text.length() <= 128 && text.matches("[a-zA-Z0-9_ .:+\\-]*") ? text : "";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
