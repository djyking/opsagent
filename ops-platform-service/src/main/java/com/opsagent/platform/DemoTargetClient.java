package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 单个固定业务目标的有界客户端；工具参数不能影响 URI 或鉴权信息。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class DemoTargetClient {
    /**
     * Safe upstream configuration outcome, distinct from uncertain transport failure.
     *
     * @author heyu
     */
    static final class ConfigurationRejected extends BusinessException {
        private final String reason;

        ConfigurationRejected(String reason, String message) {
            super(ErrorCode.CONFLICT, message);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    private final ObjectMapper json;
    private final String base;
    private final String token;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    DemoTargetClient(
            ObjectMapper json,
            @Value("${ops.operations.demo-target-url:}") String base,
            @Value("${ops.operations.demo-target-token:}") String token) {
        this.json = json;
        this.base = base.replaceAll("/+$", "");
        this.token = token;
    }

    boolean configured() {
        return !base.isBlank() && token.length() >= 32;
    }

    JsonNode snapshot() {
        JsonNode result = call("/internal/demo/snapshot", null, true);
        if (!"ISOLATED_DEMO".equals(result.path("scope").asText())
                || !DemoTargetDtos.TARGET.equals(result.path("targetCode").asText())) {
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "DEMO_TARGET_SCOPE_MISMATCH");
        }
        return result;
    }

    JsonNode preview() {
        return call("/demo/orders/preview", null, false);
    }

    JsonNode snapshot(String targetCode) {
        requireTarget(targetCode);
        if (DemoTargetDtos.TARGET.equals(targetCode)) return snapshot();
        JsonNode result = call("/internal/demo/notification/snapshot", null, true);
        if (!"ISOLATED_DEMO".equals(result.path("scope").asText())
                || !targetCode.equals(result.path("targetCode").asText()))
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "DEMO_TARGET_SCOPE_MISMATCH");
        return result;
    }

    JsonNode preview(String targetCode) {
        requireTarget(targetCode);
        return DemoTargetDtos.TARGET.equals(targetCode)
                ? preview()
                : call("/demo/notifications/preview", null, false);
    }

    private void requireTarget(String targetCode) {
        if (!DemoTargetDtos.TARGETS.contains(targetCode))
            throw new BusinessException(ErrorCode.FORBIDDEN, "DEMO_TARGET_NOT_ALLOWED");
    }

    JsonNode managedConfiguration(String id) {
        if (!java.util.Set.of("order-business", "order-runtime").contains(id)) {
            throw new BusinessException(ErrorCode.VALIDATION, "配置项未纳入管理");
        }
        return call("/internal/demo/configuration/" + id, null, true);
    }

    JsonNode publishBusinessConfiguration(
            JsonNode content, String expectedRevision, String requestId) {
        return call(
                "/internal/demo/configuration/order-business",
                Map.of(
                        "content",
                        content,
                        "expectedRevision",
                        expectedRevision,
                        "requestId",
                        requestId),
                true);
    }

    void inject(DemoTargetDtos.Incident incident) {
        requireTarget(incident.targetCode());
        call(
                DemoTargetDtos.TARGET.equals(incident.targetCode())
                        ? "/internal/demo/scenarios"
                        : "/internal/demo/notification/scenarios",
                Map.of(
                        "incidentId",
                        incident.incidentId(),
                        "scenarioCode",
                        incident.scenarioCode(),
                        "expiresAt",
                        incident.expiresAt().toString()),
                true);
    }

    void restore(DemoTargetDtos.Action request, String source) {
        call(
                "/internal/demo/actions",
                Map.of(
                        "incidentId",
                        request.incidentId(),
                        "action",
                        request.action(),
                        "expectedRevision",
                        request.expectedRevision(),
                        "recoverySource",
                        source),
                true);
    }

    void restore(String targetCode, DemoTargetDtos.Action request, String source) {
        requireTarget(targetCode);
        if (DemoTargetDtos.TARGET.equals(targetCode)) {
            restore(request, source);
            return;
        }
        call(
                "/internal/demo/notification/actions",
                Map.of(
                        "incidentId",
                        request.incidentId(),
                        "action",
                        request.action(),
                        "expectedRevision",
                        request.expectedRevision(),
                        "recoverySource",
                        source),
                true);
    }

    private JsonNode call(String path, Object body, boolean control) {
        if (!configured())
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "DEMO_TARGET_NOT_CONFIGURED");
        try {
            URI uri = URI.create(base + path);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !ListHolder.SCHEMES.contains(uri.getScheme())) {
                throw new IllegalArgumentException("FIXED_TARGET_CONFIGURATION_INVALID");
            }
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8));
            if (control) request.header("X-Demo-Control-Token", token);
            if (body == null) request.GET();
            else
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.body().length() > 32768)
                throw new IllegalStateException("RESPONSE_TOO_LARGE");
            JsonNode result = json.readTree(response.body());
            if (control && response.statusCode() != 200) {
                if (path.startsWith("/internal/demo/configuration/")) {
                    String reason = result.path("reasonCode").asText();
                    throw new ConfigurationRejected(
                            reason,
                            switch (reason) {
                                case "REVISION_CONFLICT", "NACOS_CAS_CONFLICT" ->
                                        "配置版本已变化，请刷新后重新核对";
                                case "TARGET_BUSY" -> "目标正在演练，请恢复并完成冷却后再发布配置";
                                case "CONFIG_CONFIRMATION_PENDING" -> "Nacos已接受发布，但尚未确认目标应用，请刷新核对";
                                default -> "受控配置操作未确认，请检查Nacos与目标状态";
                            });
                }
                throw new BusinessException(ErrorCode.CONFLICT, "DEMO_TARGET_ACTION_REJECTED");
            }
            if (!control
                    && (!result.path("httpStatus").canConvertToInt()
                            || result.path("httpStatus").asInt() != response.statusCode()
                            || !java.util.Set.of(200, 429, 503).contains(response.statusCode()))) {
                throw new IllegalStateException("INVALID_BUSINESS_RESPONSE");
            }
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "DEMO_TARGET_INTERRUPTED");
        } catch (Exception exception) {
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "DEMO_TARGET_UNAVAILABLE");
        }
    }

    /**
     * @author heyu
     */
    private static final class ListHolder {
        private static final java.util.Set<String> SCHEMES = java.util.Set.of("http", "https");
    }
}
