package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Single-attempt bounded client. Idempotent commands may be explicitly retried with the same key.
 *
 * @author heyu
 * @since 2026/9/3
 */
final class HarnessPlatformClient {
    private final ObjectMapper json;
    private final URI auth;
    private final URI platform;
    private final String authorization;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    HarnessPlatformClient(ObjectMapper json, String auth, String platform, String credential) {
        this.json = json;
        this.auth = origin(auth);
        this.platform = origin(platform);
        if (credential == null || !credential.matches("[A-Za-z0-9._~+/=-]{32,512}"))
            throw new IllegalArgumentException("HARNESS_APPLICATION_CREDENTIAL_INVALID");
        authorization = "Bearer " + credential;
    }

    JsonNode identity(String project, String token) {
        return exchange(
                        auth.resolve("/internal/harness/introspect"),
                        "POST",
                        json.valueToTree(Map.of("projectId", project, "userToken", token)),
                        null,
                        null,
                        null,
                        true)
                .body();
    }

    String domainAuthorization(String project, String token, String runId, long deadline) {
        var delegation =
                exchange(
                                auth.resolve("/internal/harness/delegations"),
                                "POST",
                                json.valueToTree(
                                        Map.of(
                                                "projectId",
                                                project,
                                                "userToken",
                                                token,
                                                "runId",
                                                runId,
                                                "deadline",
                                                java.time.Instant.ofEpochSecond(deadline)
                                                        .toString())),
                                null,
                                null,
                                null,
                                true)
                        .body();
        String id = delegation.path("delegationId").asText();
        if (!id.matches("[a-f0-9-]{36}"))
            throw failure(ErrorCode.MIDDLEWARE_UNAVAILABLE, "HARNESS_DELEGATION_INVALID");
        return exchange(
                        auth.resolve("/internal/harness/delegations/" + id + "/token"),
                        "POST",
                        json.valueToTree(Map.of("runId", runId, "audience", "rag")),
                        null,
                        null,
                        null,
                        true)
                .body()
                .path("authorization")
                .asText();
    }

    Reply call(String path, String method, JsonNode body, String token, String key, String etag) {
        if (!path.startsWith("/v1/projects/") || path.contains(".."))
            throw new IllegalArgumentException("HARNESS_ENDPOINT_INVALID");
        return exchange(platform.resolve(path), method, body, token, key, etag, false);
    }

    private Reply exchange(
            URI endpoint,
            String method,
            JsonNode body,
            String userToken,
            String key,
            String etag,
            boolean envelope) {
        try {
            var request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(20))
                            .header("Authorization", authorization)
                            .header("Accept", "application/json");
            if (userToken != null) request.header("X-Harness-User-Token", userToken);
            if (key != null) request.header("Idempotency-Key", key);
            if (etag != null) request.header("If-Match", etag);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body.toString()));
            var response = HarnessHttpTransport.send(http, request.build(), 1024 * 1024);
            byte[] bytes = response.body();
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw failure(ErrorCode.FORBIDDEN, "HARNESS_ACCESS_REVOKED");
            if (response.statusCode() == 404)
                throw failure(ErrorCode.NOT_FOUND, "HARNESS_RESOURCE_NOT_FOUND");
            if (response.statusCode() == 400 || response.statusCode() == 422)
                throw failure(ErrorCode.VALIDATION, "HARNESS_COMMAND_INPUT_INVALID");
            if (response.statusCode() == 409
                    || response.statusCode() == 412
                    || response.statusCode() == 428)
                throw failure(ErrorCode.CONFLICT, "HARNESS_REVISION_OR_COMMAND_CONFLICT");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw failure(ErrorCode.MIDDLEWARE_UNAVAILABLE, "HARNESS_UPSTREAM_REJECTED");
            JsonNode result = json.readTree(bytes);
            if (envelope) {
                if (!result.path("code").isIntegralNumber()
                        || !result.path("code").canConvertToInt()
                        || result.path("code").asInt(-1) != 0)
                    throw failure(ErrorCode.FORBIDDEN, "HARNESS_IDENTITY_DENIED");
                result = result.path("data");
            }
            return new Reply(result, response.headers().firstValue("ETag").orElse(null));
        } catch (BusinessException failure) {
            throw failure;
        } catch (HarnessHttpTransport.ResponseTooLargeException tooLarge) {
            throw failure(ErrorCode.MIDDLEWARE_UNAVAILABLE, "HARNESS_RESPONSE_TOO_LARGE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(ErrorCode.MIDDLEWARE_UNAVAILABLE, "HARNESS_UPSTREAM_OUTCOME_UNKNOWN");
        } catch (Exception unavailable) {
            throw failure(ErrorCode.MIDDLEWARE_UNAVAILABLE, "HARNESS_UPSTREAM_OUTCOME_UNKNOWN");
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
                                        .contains(uri.getHost())))
            throw new IllegalArgumentException("HARNESS_ORIGIN_INVALID");
        return uri;
    }

    private static BusinessException failure(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }

    /**
     * @author heyu
     */
    record Reply(JsonNode body, String etag) {}
}
