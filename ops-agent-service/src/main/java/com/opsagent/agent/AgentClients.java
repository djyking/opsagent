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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
        if (actor.path("userId").asLong() != context.userId())
            throw new AgentAccessFailure("ACTOR_ID_MISMATCH");
        if (!actor.path("active").asBoolean()) throw new AgentAccessFailure(inactiveReason(actor));
        List<String> roles =
                context.roles().stream()
                        .filter(
                                role -> {
                                    for (JsonNode current : actor.path("roles"))
                                        if (current.asText().equals(role)) return true;
                                    return false;
                                })
                        .toList();
        if (roles.isEmpty()) throw new AgentAccessFailure("ACTOR_ROLE_REVOKED");
        Instant expiry = context.validUntil();
        if (!actor.path("expiresAt").isNull() && actor.hasNonNull("expiresAt")) {
            Instant lease = Instant.parse(actor.path("expiresAt").asText());
            if (lease.isBefore(expiry)) expiry = lease;
        } else if (roles.contains("DEMO")) throw new AgentAccessFailure("ACTOR_LEASE_REQUIRED");
        if (!expiry.isAfter(Instant.now())) throw new AgentAccessFailure("RUN_DEADLINE_EXPIRED");
        return new Context(
                context.userId(),
                actor.path("username").asText(),
                roles,
                context.runId(),
                context.targetCode(),
                expiry);
    }

    /**
     * Read identity status even after a run deadline; this context is never returned for tool use.
     */
    JsonNode inspectActor(Context original) {
        Context query =
                new Context(
                        original.userId(),
                        original.username(),
                        original.roles(),
                        "identity-status",
                        original.targetCode(),
                        Instant.now().plusSeconds(30));
        return call("auth", "/internal/agent/actors/" + original.userId(), "GET", null, query);
    }

    static String inactiveReason(JsonNode actor) {
        String reason = actor.path("reasonCode").asText();
        if (List.of("VISITOR_REVOKED", "VISITOR_LEASE_EXPIRED", "ACTOR_DISABLED").contains(reason))
            return reason;
        if (actor.path("revoked").asBoolean() || actor.hasNonNull("revokedAt"))
            return "VISITOR_REVOKED";
        if (actor.hasNonNull("expiresAt")
                && !Instant.parse(actor.path("expiresAt").asText()).isAfter(Instant.now()))
            return "VISITOR_LEASE_EXPIRED";
        return "ACTOR_DISABLED";
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
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            Duration timeout = requestTimeout(audience, path, actor);
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(URI.create(base + path))
                            .timeout(timeout)
                            .header("Authorization", "Bearer " + tokens.issue(audience, actor))
                            .header("Content-Type", "application/json");
            request.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body.toString()));
            // JDK request timeout can end at response headers; bound the complete body as well.
            pending = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response =
                    pending.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (response.body().length() > 300000) throw AgentJson.invalid("工具响应超出证据上限");
            JsonNode result = AgentJson.read(response.body());
            if (response.statusCode() / 100 != 2 || result.path("code").asInt(-1) != 0) {
                String message = result.path("message").asText("内部服务暂不可用");
                if (message.length() > 300) message = message.substring(0, 300);
                if (List.of(
                                "VISITOR_REVOKED",
                                "VISITOR_LEASE_EXPIRED",
                                "ACTOR_DISABLED",
                                "ACTOR_ROLE_REVOKED",
                                "ACTOR_LEASE_REQUIRED",
                                "ACTOR_LEASE_EXPIRED",
                                "ACTOR_ID_MISMATCH",
                                "ACTOR_INACTIVE")
                        .contains(message)) throw new AgentAccessFailure(message);
                throw new BusinessException(
                        failureCode(response.statusCode(), result.path("code").asInt(-1)), message);
            }
            return result.path("data");
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "内部调用中断；保留执行意图");
        } catch (Exception exception) {
            if (pending != null) pending.cancel(true);
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "内部调用失败或响应未知；未隐式重试");
        }
    }

    private static Duration requestTimeout(String audience, String path, Context actor) {
        // A model decision may use the configured 120 seconds; allow receipt transit while
        // remaining inside the 150-second worker lease and the original run/actor deadline.
        Duration configured =
                Duration.ofSeconds(
                        audience.equals("rag") ? path.equals("/internal/ai/turns") ? 130 : 65 : 12);
        Duration remaining = Duration.between(Instant.now(), actor.validUntil());
        if (remaining.isNegative() || remaining.isZero())
            throw new AgentAccessFailure("RUN_DEADLINE_EXPIRED");
        return remaining.compareTo(configured) < 0 ? remaining : configured;
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
