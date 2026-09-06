package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 使用既有工单内部读入口核验归属与运行目标；不直连工单库或复用登录令牌。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class DiagnosticTicketClient {
    private final ObjectMapper json;
    private final InternalActorTokens tokens;
    private final String baseUrl;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    DiagnosticTicketClient(
            ObjectMapper json,
            @Value("${ops.agent.internal-secret:${OPS_AGENT_INTERNAL_SECRET:}}") String secret,
            @Value("${OPS_TICKET_INTERNAL_URL:http://localhost:8102}") String baseUrl) {
        this.json = json;
        this.tokens = new InternalActorTokens(secret);
        this.baseUrl = baseUrl;
    }

    JsonNode authorized(long ticketId, InternalActorTokens.Context actor) {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/internal/agent/tickets/" + ticketId))
                        .timeout(Duration.ofSeconds(3))
                        .header("Authorization", "Bearer " + tokens.issue("ticket", actor))
                        .GET()
                        .build();
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            var response = future.get(4, TimeUnit.SECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权将该工单加入观测证据");
            }
            if (response.statusCode() == 404)
                throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在或不可见");
            if (response.statusCode() != 200 || response.body().length > 128 * 1024) {
                throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "工单权限核验暂不可用");
            }
            JsonNode body = json.readTree(response.body());
            if (!"0".equals(body.path("code").asText()) || !body.path("data").isObject()) {
                throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "工单权限响应不可用");
            }
            if (!actor.targetCode().equals(body.path("data").path("affectedCiCode").asText())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "工单与证据服务身份不匹配");
            }
            return body.path("data");
        } catch (BusinessException denied) {
            throw denied;
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "无法重新核验工单权限");
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }
}
