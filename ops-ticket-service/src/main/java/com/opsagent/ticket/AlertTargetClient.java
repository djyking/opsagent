package com.opsagent.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Use the same CMDB identity resolver as topology; transport failures are retryable, not a mapping.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class AlertTargetClient {
    private final InternalActorTokens tokens;
    private final RestClient http;
    private final String platformUrl;

    AlertTargetClient(
            @Value("${ops.agent.internal-secret:${OPS_AGENT_INTERNAL_SECRET:}}") String secret,
            @Value("${ops.agent.platform-url:${OPS_PLATFORM_INTERNAL_URL:http://localhost:8105}}")
                    String platformUrl) {
        tokens = new InternalActorTokens(secret);
        this.platformUrl = platformUrl.replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        http = RestClient.builder().requestFactory(factory).build();
    }

    Resolution resolve(JsonNode labels, String episodeId) {
        var request = JsonNodeFactory.instance.objectNode();
        for (String field :
                List.of("service_ci_code", "ci_code", "service", "job", "environment")) {
            if (labels.has(field)) request.set(field, labels.path(field));
        }
        var actor =
                new InternalActorTokens.Context(
                        Long.MAX_VALUE,
                        "alertmanager-linker",
                        List.of("SYSTEM"),
                        episodeId,
                        "alert-target-resolution",
                        Instant.now().plusSeconds(90));
        try {
            JsonNode response =
                    http.post()
                            .uri(platformUrl + "/internal/platform/alert-targets/resolve")
                            .headers(h -> h.setBearerAuth(tokens.issue("platform", actor)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null || response.path("code").asInt(-1) != 0)
                throw new IllegalStateException("invalid identity response");
            JsonNode data = response.path("data");
            String status = data.path("status").asText();
            String target = data.path("targetCode").asText();
            String environment = data.path("environment").asText();
            String message = data.path("message").asText();
            boolean matched = "MATCHED".equals(status);
            if (!status.matches("[A-Z_]{1,40}")
                    || message.isBlank()
                    || message.length() > 500
                    || matched
                            && (!target.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
                                    || !environment.matches("[A-Z0-9_-]{1,32}"))
                    || !matched && (!target.isBlank() || !environment.isBlank()))
                throw new IllegalStateException("invalid identity result");
            return new Resolution(status, target, environment, message);
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "告警服务关联解析暂不可用，请等待重新投递");
        }
    }

    record Resolution(String status, String targetCode, String environment, String message) {
        boolean matched() {
            return "MATCHED".equals(status);
        }
    }
}
