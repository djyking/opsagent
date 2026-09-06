package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.InternalActorTokens.Context;
import com.opsagent.common.security.OpsPrincipal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 固定内部服务适配器；不接受模型指定 URL，不转存用户 JWT。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class AgentClients {
    private final InternalActorTokens tokens;
    private final Map<String, String> urls;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    AgentClients(
            @Value("${ops.agent.internal-secret:}") String secret,
            @Value("${ops.agent.auth-url}") String auth,
            @Value("${ops.agent.ticket-url}") String ticket,
            @Value("${ops.agent.rag-url}") String rag,
            @Value("${ops.agent.platform-url}") String platform) {
        tokens = new InternalActorTokens(secret);
        urls = Map.of("auth", auth, "ticket", ticket, "rag", rag, "platform", platform);
    }

    Context current(OpsPrincipal principal, String run) {
        return refresh(
                new Context(
                        principal.userId(),
                        principal.username(),
                        principal.roles(),
                        run,
                        "ops-demo-order-service",
                        Instant.now().plusSeconds(900)));
    }

    Context refresh(Context context) {
        JsonNode actor =
                call("auth", "/internal/agent/actors/" + context.userId(), "GET", null, context);
        if (!actor.path("active").asBoolean() || actor.path("userId").asLong() != context.userId())
            throw denied();
        List<String> roles =
                context.roles().stream()
                        .filter(
                                role -> {
                                    for (JsonNode current : actor.path("roles"))
                                        if (current.asText().equals(role)) return true;
                                    return false;
                                })
                        .toList();
        if (roles.isEmpty()) throw denied();
        Instant expiry = context.validUntil();
        if (!actor.path("expiresAt").isNull() && actor.hasNonNull("expiresAt")) {
            Instant lease = Instant.parse(actor.path("expiresAt").asText());
            if (lease.isBefore(expiry)) expiry = lease;
        } else if (roles.contains("DEMO")) throw denied();
        if (!expiry.isAfter(Instant.now())) throw denied();
        return new Context(
                context.userId(),
                actor.path("username").asText(),
                roles,
                context.runId(),
                context.targetCode(),
                expiry);
    }

    Context fromState(JsonNode state) {
        JsonNode actor = state.path("actor");
        List<String> roles =
                AgentJson.MAPPER.convertValue(
                        actor.path("roles"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        return new Context(
                actor.path("userId").asLong(),
                actor.path("username").asText(),
                roles,
                state.path("runId").asText(),
                state.path("targetCode").asText(AgentTargets.ORDER),
                Instant.parse(actor.path("validUntil").asText()));
    }

    JsonNode call(String audience, String path, String method, JsonNode body, Context actor) {
        String base = urls.get(audience);
        if (base == null || !path.startsWith("/internal/")) throw AgentJson.invalid("内部目标无效");
        try {
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(URI.create(base + path))
                            .timeout(Duration.ofSeconds(audience.equals("rag") ? 65 : 12))
                            .header("Authorization", "Bearer " + tokens.issue(audience, actor))
                            .header("Content-Type", "application/json");
            request.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body.toString()));
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.body().length() > 300000) throw AgentJson.invalid("工具响应超出证据上限");
            JsonNode result = AgentJson.read(response.body());
            if (response.statusCode() / 100 != 2 || result.path("code").asInt(-1) != 0) {
                String message = result.path("message").asText("内部服务暂不可用");
                if (message.length() > 300) message = message.substring(0, 300);
                throw new BusinessException(
                        failureCode(response.statusCode(), result.path("code").asInt(-1)), message);
            }
            return result.path("data");
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "内部调用中断；保留执行意图");
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "内部调用失败或响应未知；未隐式重试");
        }
    }

    private static ErrorCode failureCode(int status, int code) {
        // Internal business failures use a standard envelope even when HTTP status is 200.
        return switch (status) {
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            default ->
                    switch (code) {
                        case 40100 -> ErrorCode.UNAUTHENTICATED;
                        case 40300 -> ErrorCode.FORBIDDEN;
                        case 40400 -> ErrorCode.NOT_FOUND;
                        default -> ErrorCode.MIDDLEWARE_UNAVAILABLE;
                    };
        };
    }

    ObjectNode actorJson(Context actor) {
        ObjectNode result =
                AgentJson.object()
                        .put("userId", actor.userId())
                        .put("username", actor.username())
                        .put("validUntil", actor.validUntil().toString());
        result.set("roles", AgentJson.tree(actor.roles()));
        return result;
    }

    static BusinessException denied() {
        return new BusinessException(ErrorCode.FORBIDDEN, "当前身份、租约或演练范围不允许此操作");
    }
}
