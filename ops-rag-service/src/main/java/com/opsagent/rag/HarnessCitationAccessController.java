package com.opsagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Checks stored citation visibility through Knowledge using the original restricted run identity.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class HarnessCitationAccessController {
    private final InternalActorTokens tokens;
    private final InternalActorAccess access;
    private final URI knowledge;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    HarnessCitationAccessController(
            @Value("${OPS_AGENT_INTERNAL_SECRET:}") String secret,
            @Value("${OPS_AUTH_INTERNAL_URL:${OPS_AGENT_AUTH_URL:http://ops-auth-service:8101}}")
                    String authUrl,
            @Value("${OPS_KNOWLEDGE_INTERNAL_URL:http://ops-knowledge-service:8103}")
                    String knowledgeUrl) {
        tokens = new InternalActorTokens(secret);
        access = new InternalActorAccess(tokens, authUrl);
        URI origin = URI.create(knowledgeUrl);
        if (origin.getHost() == null
                || origin.getUserInfo() != null
                || origin.getQuery() != null
                || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))
                || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme())))
            throw new IllegalArgumentException("Invalid internal Knowledge origin");
        knowledge = origin.resolve("/internal/agent/validate-citations");
    }

    @PostMapping("/internal/rag/validate-citations")
    ApiResponse<Verdict> validate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody Request request) {
        var actor = access.verify(authorization, "rag");
        try {
            var outbound =
                    HttpRequest.newBuilder(knowledge)
                            .timeout(Duration.ofSeconds(5))
                            .header("Authorization", "Bearer " + tokens.issue("knowledge", actor))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofByteArray(
                                            json.writeValueAsBytes(request)))
                            .build();
            var response = http.send(outbound, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                byte[] bytes = body.readNBytes(4097);
                if (response.statusCode() != 200 || bytes.length > 4096) return denied();
                var envelope = json.readTree(bytes);
                boolean allowed =
                        envelope.path("code").isIntegralNumber()
                                && envelope.path("code").canConvertToInt()
                                && envelope.path("code").asInt() == 0
                                && envelope.path("data").path("allowed").isBoolean()
                                && envelope.path("data").path("allowed").asBoolean();
                return ApiResponse.success(new Verdict(allowed));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return denied();
        } catch (Exception unavailable) {
            return denied();
        }
    }

    private ApiResponse<Verdict> denied() {
        return ApiResponse.success(new Verdict(false));
    }

    /**
     * @author heyu @since 2026/9/3
     */
    record Citation(@Min(1) long documentId, @Min(1) long chunkId, @Min(1) int version) {}

    /**
     * @author heyu @since 2026/9/3
     */
    record Request(
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Citation> citations,
            boolean allowUnpublished) {
        Request(List<Citation> citations) {
            this(citations, false);
        }
    }

    /**
     * @author heyu @since 2026/9/3
     */
    record Verdict(boolean allowed) {}
}
