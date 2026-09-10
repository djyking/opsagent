package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reads the authority's absolute lease; uploads never extend an experience.
 *
 * @author heyu
 */
@Component
class VisitorKnowledgeLeaseClient {
    private final InternalActorTokens tokens;
    private final RestClient client;

    VisitorKnowledgeLeaseClient(
            @Value("${OPS_AGENT_INTERNAL_SECRET:}") String secret,
            @Value("${OPS_AUTH_INTERNAL_URL:${OPS_AGENT_AUTH_URL:http://localhost:8101}}")
                    String authUrl) {
        tokens = new InternalActorTokens(secret);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().baseUrl(authUrl).requestFactory(factory).build();
    }

    record Lease(boolean active, Instant expiresAt) {}

    Lease read(long userId) {
        try {
            var context =
                    new InternalActorTokens.Context(
                            userId,
                            "访客",
                            List.of("DEMO"),
                            "knowledge-experience",
                            "knowledge",
                            Instant.now().plusSeconds(60));
            JsonNode response =
                    client.get()
                            .uri("/internal/agent/actors/" + userId)
                            .headers(h -> h.setBearerAuth(tokens.issue("auth", context)))
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null
                    || response.path("code").asInt(-1) != 0
                    || response.path("data").path("userId").asLong() != userId
                    || !response.path("data").hasNonNull("expiresAt"))
                throw new IllegalStateException("Invalid lease response");
            var data = response.path("data");
            Instant expires = Instant.parse(data.path("expiresAt").asText());
            return new Lease(
                    data.path("active").asBoolean(false) && expires.isAfter(Instant.now()),
                    expires);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "体验身份核验暂不可用，请稍后重试");
        }
    }
}
