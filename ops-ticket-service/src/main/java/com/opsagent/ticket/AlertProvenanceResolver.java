package com.opsagent.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 演练归属由平台活动窗口确认，监控标签不能授予用户或隔离环境权限。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class AlertProvenanceResolver {
    private final InternalActorTokens tokens;
    private final RestClient http;
    private final String platformUrl;

    AlertProvenanceResolver(
            @Value("${ops.agent.internal-secret:${OPS_AGENT_INTERNAL_SECRET:}}") String secret,
            @Value("${ops.agent.platform-url:${OPS_PLATFORM_INTERNAL_URL:http://localhost:8105}}")
                    String platformUrl) {
        this.tokens = new InternalActorTokens(secret);
        this.platformUrl = platformUrl.replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(4));
        http = RestClient.builder().requestFactory(factory).build();
    }

    TicketService.AlertProvenance resolve(String target, Instant startsAt, String episodeId) {
        String targetPath =
                switch (target == null ? "" : target) {
                    case "ops-demo-order-service" -> "order";
                    case "ops-demo-notification-service" -> "notification";
                    default -> "";
                };
        if (targetPath.isEmpty()) return TicketService.AlertProvenance.core();
        var context =
                new InternalActorTokens.Context(
                        Long.MAX_VALUE,
                        "alertmanager-linker",
                        List.of("SYSTEM"),
                        episodeId,
                        target,
                        Instant.now().plusSeconds(90));
        try {
            JsonNode response =
                    http.get()
                            .uri(
                                    platformUrl
                                            + "/internal/platform/demo-targets/"
                                            + targetPath
                                            + "/incident-owner"
                                            + "?targetCode={target}&startsAt={startsAt}",
                                    target,
                                    startsAt.toString())
                            .headers(h -> h.setBearerAuth(tokens.issue("platform", context)))
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null || response.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("可信演练归属不可用");
            }
            JsonNode owner = response.path("data");
            String id = owner.path("incidentId").asText();
            long ownerId = owner.path("ownerId").asLong();
            if (id.isBlank() || id.length() > 128 || ownerId == 0 || ownerId == Long.MAX_VALUE) {
                throw new IllegalStateException("可信演练归属无效");
            }
            return new TicketService.AlertProvenance(id, ownerId, true);
        } catch (HttpClientErrorException.NotFound exception) {
            return TicketService.AlertProvenance.core();
        } catch (RuntimeException exception) {
            // Roll back the webhook transaction so Alertmanager can retry; never invent an owner.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "演练归属解析暂不可用");
        }
    }
}
