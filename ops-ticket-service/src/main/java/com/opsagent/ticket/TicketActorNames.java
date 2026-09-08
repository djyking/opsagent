package com.opsagent.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

/**
 * 只为已通过工单可见性校验的处理人读取显示名称，不返回人员权限或凭据。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class TicketActorNames {
    private final RestClient auth;
    private final InternalActorTokens tokens;

    TicketActorNames(
            @Value("${ops.event.auth-url:${OPS_AUTH_INTERNAL_URL:http://127.0.0.1:8101}}")
                    String url,
            @Value("${ops.agent.internal-secret:${OPS_AGENT_INTERNAL_SECRET:}}") String secret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(1500);
        auth = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        tokens = new InternalActorTokens(secret);
    }

    String name(Long id) {
        if (id == null) return "待分配";
        var current = SecurityUsers.current();
        if (id == current.userId()) return current.username();
        if (id != 0 && tokens.configured()) {
            try {
                String token =
                        tokens.issue(
                                "auth",
                                new InternalActorTokens.Context(
                                        id,
                                        "event-display-name",
                                        List.of("USER"),
                                        "event-queue-name",
                                        "",
                                        Instant.now().plusSeconds(30)));
                JsonNode response =
                        auth.get()
                                .uri("/internal/agent/actors/{id}", id)
                                .header("Authorization", "Bearer " + token)
                                .retrieve()
                                .body(JsonNode.class);
                if (response != null
                        && response.path("code").asInt(-1) == 0
                        && response.path("data").path("active").asBoolean()) {
                    String name = response.path("data").path("username").asText();
                    if (!name.isBlank() && name.length() <= 128) return name;
                }
            } catch (RuntimeException ignored) {
                // 名称不可用不阻断队列；不将服务响应或认证参数回显。
            }
        }
        return "用户 #" + id;
    }
}
