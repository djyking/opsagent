package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Explicit read-only ingress using current login identities and durable request ownership. It does
 * not replace the answer-producing /api/rag/ask endpoint or migrate ticket workflows.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@ConditionalOnProperty(name = "ops.harness.search.enabled", havingValue = "true")
public class HarnessSearchController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HarnessRequestRoutes routes;
    private final InternalAgentController legacy;
    private final URI authOrigin;
    private final URI platformOrigin;
    private final String applicationAuthorization;
    private final String project;
    private final String newOwner;
    private final String agentId;
    private final String releaseId;
    private final String releaseDigest;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public HarnessSearchController(
            JdbcTemplate jdbc,
            InternalAgentController legacy,
            @Value("${ops.harness.auth-origin}") String authOrigin,
            @Value("${ops.harness.platform-origin}") String platformOrigin,
            @Value("${ops.harness.application-credential}") String applicationCredential,
            @Value("${ops.harness.project}") String project,
            @Value("${ops.harness.search.new-owner:LEGACY}") String newOwner,
            @Value("${ops.harness.search.agent-id:ops-readonly}") String agentId,
            @Value("${ops.harness.search.release-id:}") String releaseId,
            @Value("${ops.harness.search.release-digest:}") String releaseDigest) {
        this.routes = new HarnessRequestRoutes(jdbc);
        this.legacy = legacy;
        this.authOrigin = origin(authOrigin);
        this.platformOrigin = origin(platformOrigin);
        if (!applicationCredential.matches("[A-Za-z0-9._~+/=-]{32,512}")) {
            throw new IllegalArgumentException("HARNESS_APPLICATION_CREDENTIAL_INVALID");
        }
        this.applicationAuthorization = "Bearer " + applicationCredential;
        this.project = project;
        this.newOwner = newOwner;
        this.agentId = agentId;
        this.releaseId = releaseId;
        this.releaseDigest = releaseDigest;
    }

    @PostMapping("/api/rag/harness-search")
    public ResponseEntity<?> search(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody Search request) {
        if (!authorization.startsWith("Bearer ") || authorization.length() > 10000) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "HARNESS_LOGIN_REQUIRED");
        }
        String userToken = authorization.substring(7);
        JsonNode identity =
                post(
                        authOrigin.resolve("/internal/harness/introspect"),
                        JSON.valueToTree(Map.of("projectId", project, "userToken", userToken)),
                        null,
                        null,
                        true);
        String subject = identity.path("subject").asText();
        if (!subject.equals(Long.toString(SecurityUsers.current().userId()))
                || !project.equals(identity.path("projectId").asText())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "HARNESS_SUBJECT_MISMATCH");
        }
        JsonNode input =
                JSON.createObjectNode().put("query", request.query()).put("topK", request.topK());
        JsonNode configured =
                JSON.valueToTree(
                        Map.of(
                                "releaseRef",
                                Map.of(
                                        "agentId",
                                        agentId,
                                        "releaseId",
                                        releaseId,
                                        "digest",
                                        releaseDigest),
                                "inputs",
                                input));
        HarnessRequestRoutes.Route route =
                routes.assign(
                        identity.path("applicationId").asText(),
                        project,
                        subject,
                        request.requestId(),
                        HarnessRequestRoutes.hash(input.toString()),
                        newOwner,
                        configured.toString());
        if ("HARNESS".equals(route.owner())) {
            JsonNode body = dispatch(route.dispatchJson());
            JsonNode accepted =
                    post(
                            platformOrigin.resolve("/v1/projects/" + project + "/runs"),
                            body,
                            userToken,
                            route.id(),
                            false);
            return ResponseEntity.accepted()
                    .header("Cache-Control", "no-store")
                    .body(
                            ApiResponse.success(
                                    Map.of(
                                            "owner",
                                            route.owner(),
                                            "routeId",
                                            route.id(),
                                            "run",
                                            accepted)));
        }
        // LEGACY is read-only: a retry may repeat authorized retrieval to avoid serving stale ACL
        // results.
        // The route remains LEGACY even if the platform switch changes or retrieval fails.
        JsonNode delegated =
                post(
                        authOrigin.resolve("/internal/harness/delegations"),
                        JSON.valueToTree(
                                Map.of(
                                        "projectId",
                                        project,
                                        "userToken",
                                        userToken,
                                        "runId",
                                        "legacy-" + route.id(),
                                        "deadline",
                                        routeDeadline(route).toString())),
                        null,
                        null,
                        true);
        JsonNode minted =
                post(
                        authOrigin.resolve(
                                "/internal/harness/delegations/"
                                        + delegated.path("delegationId").asText()
                                        + "/token"),
                        JSON.valueToTree(
                                Map.of("runId", "legacy-" + route.id(), "audience", "rag")),
                        null,
                        null,
                        true);
        var result =
                legacy.search(
                        minted.path("authorization").asText(),
                        new InternalAgentDtos.SearchRequest(request.query(), request.topK()));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        ApiResponse.success(
                                Map.of(
                                        "owner",
                                        route.owner(),
                                        "routeId",
                                        route.id(),
                                        "status",
                                        "COMPLETED",
                                        "result",
                                        result.data())));
    }

    private Instant routeDeadline(HarnessRequestRoutes.Route route) {
        // Persistent creation time is encoded nowhere in the route UUID: read it from the ledger.
        return routes.deadline(route.id());
    }

    private JsonNode dispatch(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception invalid) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HARNESS_ROUTE_CORRUPT");
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> failure(Exception failure) {
        int status =
                failure instanceof BusinessException business
                        ? business.getErrorCode().code() / 100
                        : 400;
        if (status < 400 || status > 599) status = 503;
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(ApiResponse.failure(status * 100, "HARNESS_SEARCH_REQUEST_REJECTED"));
    }

    private JsonNode post(
            URI endpoint, JsonNode body, String userToken, String key, boolean envelope) {
        try {
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", applicationAuthorization)
                            .header("Content-Type", "application/json");
            if (userToken != null) request.header("X-Harness-User-Token", userToken);
            if (key != null) request.header("Idempotency-Key", key);
            HttpResponse<java.io.InputStream> response =
                    http.send(
                            request.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var stream = response.body()) {
                bytes = stream.readNBytes(1024 * 1024 + 1);
            }
            if (bytes.length > 1024 * 1024
                    || response.statusCode() < 200
                    || response.statusCode() >= 300) {
                throw new BusinessException(
                        ErrorCode.MIDDLEWARE_UNAVAILABLE, "HARNESS_UPSTREAM_REJECTED");
            }
            JsonNode result = JSON.readTree(bytes);
            if (envelope) {
                if (!result.path("code").isIntegralNumber()
                        || !result.path("code").canConvertToInt()
                        || result.path("code").asInt() != 0) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "HARNESS_IDENTITY_DENIED");
                }
                return result.path("data");
            }
            return result;
        } catch (BusinessException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "HARNESS_UPSTREAM_UNAVAILABLE");
        } catch (Exception unavailable) {
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "HARNESS_UPSTREAM_UNAVAILABLE");
        }
    }

    private static URI origin(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || !("https".equals(uri.getScheme())
                        || "http".equals(uri.getScheme())
                                && Set.of("127.0.0.1", "localhost", "[::1]")
                                        .contains(uri.getHost()))) {
            throw new IllegalArgumentException("HARNESS_ORIGIN_INVALID");
        }
        return uri;
    }

    /**
     * @author heyu
     */
    public record Search(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 2000) String query,
            @Min(1) @Max(20) int topK) {}
}
