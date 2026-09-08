package com.opsagent.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 受控演练复用已有工作区恢复判定，人工文字不能替代机器证据。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class EventRecoveryVerifier {
    private final RestClient client;
    private final HttpServletRequest request;
    private final RestClient platform;
    private final EventRecoveryRules rules;

    EventRecoveryVerifier(
            @Value("${ops.event.agent-url:${OPS_AGENT_INTERNAL_URL:http://localhost:8106}}")
                    String url,
            HttpServletRequest request,
            @Value("${ops.event.platform-url:http://localhost:8105}") String platformUrl,
            EventRecoveryRules rules) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.request = request;
        platform = RestClient.builder().baseUrl(platformUrl).requestFactory(factory).build();
        this.rules = rules;
    }

    void verify(Ticket ticket) {
        verify(ticket, ticket.getCreateTime());
    }

    void verify(Ticket ticket, LocalDateTime resultAt) {
        if (!EventRecoveryBindingService.isolated(ticket)) {
            verifyRegular(ticket, resultAt);
            return;
        }
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) throw conflict();
            JsonNode response =
                    client.get()
                            .uri("/api/automation/tickets/{id}/workspace", ticket.getId())
                            .header("Authorization", authorization)
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null
                    || response.path("code").asInt(-1) != 0
                    || !verified(response.path("data"), ticket)) throw conflict();
        } catch (RuntimeException failure) {
            throw conflict();
        }
    }

    private void verifyRegular(Ticket ticket, LocalDateTime resultAt) {
        String environment = rules.environment(ticket.getEnvironment(), ticket.getAffectedCiCode());
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ") || resultAt == null)
                throw conflict();
            watch(ticket, environment, authorization, false);
            var response =
                    platform.get()
                            .uri(
                                    builder ->
                                            builder.path(
                                                            "/api/platform/observability/services/{code}"
                                                                + "/recovery-evidence")
                                                    .queryParam("environment", environment)
                                                    .queryParam("ticketId", ticket.getId())
                                                    .build(ticket.getAffectedCiCode()))
                            .header("Authorization", authorization)
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null
                    || response.path("code").asInt(-1) != 0
                    || response.path("data").path("ticketId").asLong(-1) != ticket.getId())
                throw conflict();
            String blocker =
                    rules.blocker(
                            response.path("data"),
                            ticket.getAffectedCiCode(),
                            environment,
                            resultAt.atZone(ZoneId.systemDefault()).toInstant(),
                            Instant.now());
            if (!blocker.isBlank()) throw new BusinessException(ErrorCode.CONFLICT, blocker);
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw new BusinessException(ErrorCode.CONFLICT, "真实恢复证据暂不可读，保留当前状态，请核对采集与巡检记录");
        }
    }

    void updateWatch(Ticket ticket, boolean stop) {
        if (EventRecoveryBindingService.isolated(ticket)) return;
        try {
            String environment =
                    rules.environment(ticket.getEnvironment(), ticket.getAffectedCiCode());
            watch(ticket, environment, request.getHeader("Authorization"), stop);
        } catch (RuntimeException unavailable) {
            // The handling result is retained; the next verification retries registration and
            // remains blocked until real observation has completed.
        }
    }

    void validateBindingTarget(String target, String environment) {
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) throw conflict();
            var response =
                    platform.get()
                            .uri("/api/platform/observability/services/{code}?timeRange=5m", target)
                            .header("Authorization", authorization)
                            .retrieve()
                            .body(JsonNode.class);
            JsonNode node = response == null ? null : response.path("data").path("node");
            if (response == null
                    || response.path("code").asInt(-1) != 0
                    || node == null
                    || !target.equals(node.path("ciCode").asText())
                    || !environment.equals(node.path("environment").asText()))
                throw new BusinessException(ErrorCode.CONFLICT, "所选服务和观测环境与服务登记不符，请核对实际目标");
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw new BusinessException(ErrorCode.CONFLICT, "服务登记暂不可读取，未修改恢复关联，请稍后核对");
        }
    }

    private void watch(Ticket ticket, String environment, String authorization, boolean stop) {
        var uri =
                "/api/platform/observability/services/"
                        + ticket.getAffectedCiCode()
                        + "/recovery-observations?environment="
                        + environment
                        + "&ticketId="
                        + ticket.getId();
        JsonNode result =
                (stop ? platform.delete().uri(uri) : platform.post().uri(uri))
                        .header("Authorization", authorization)
                        .retrieve()
                        .body(JsonNode.class);
        if (result == null || result.path("code").asInt(-1) != 0)
            throw new BusinessException(ErrorCode.CONFLICT, "普通事件恢复观察暂不可登记或更新，请核对关联工单与采集服务状态");
    }

    static boolean verified(JsonNode data, Ticket ticket) {
        JsonNode verification = data.path("verification");
        try {
            Instant generated = Instant.parse(data.path("generatedAt").asText());
            Instant observed = Instant.parse(verification.path("observedAt").asText());
            Instant now = Instant.now();
            return data.path("ticketId").asLong() == ticket.getId()
                    && ticket.getIncidentId() != null
                    && ticket.getIncidentId().equals(data.path("incidentId").asText())
                    && ticket.getAffectedCiCode().equals(data.path("targetCode").asText())
                    && "CURRENT".equals(verification.path("scope").asText())
                    && "RECOVERED".equals(verification.path("status").asText())
                    && verification.path("incidentMatched").asBoolean()
                    && verification.path("businessHealthy").asBoolean()
                    && verification.path("alertResolved").asBoolean()
                    && verification.path("consecutiveSuccesses").asInt() >= 3
                    && generated.isAfter(now.minusSeconds(20))
                    && !generated.isAfter(now.plusSeconds(2))
                    && observed.isAfter(now.minusSeconds(20))
                    && !observed.isAfter(now.plusSeconds(2));
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "受控事件尚无同一现场的有效恢复验证，请先核对真实探针及告警恢复");
    }
}
