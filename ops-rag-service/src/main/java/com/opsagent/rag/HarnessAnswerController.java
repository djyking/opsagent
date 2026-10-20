package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Authenticated async controls preserve platform revision and idempotency semantics.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@ConditionalOnProperty(name = "ops.harness.answer.enabled", havingValue = "true")
public class HarnessAnswerController {
    private final HarnessAnswerService answers;
    private final InternalActorAccess actors;

    public HarnessAnswerController(
            HarnessAnswerService answers,
            @Value("${OPS_AGENT_INTERNAL_SECRET:}") String secret,
            @Value("${OPS_AUTH_INTERNAL_URL:${OPS_AGENT_AUTH_URL:http://ops-auth-service:8101}}")
                    String auth) {
        this.answers = answers;
        actors = new InternalActorAccess(new InternalActorTokens(secret), auth);
    }

    @GetMapping("/api/rag/harness/answers/{id}")
    ResponseEntity<?> status(
            @RequestHeader("Authorization") String authorization, @PathVariable String id) {
        return response(answers.status(authorization, id));
    }

    @GetMapping("/api/rag/harness/answers/{id}/events")
    ResponseEntity<?> events(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String id,
            @RequestParam(required = false) String cursor) {
        String suffix = "/events?limit=50";
        if (cursor != null)
            suffix += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        return reply(200, answers.proxy(authorization, id, suffix, "GET", null, null, null));
    }

    @GetMapping("/api/rag/harness/answers/{id}/{resource:approval|unknown-invocation}")
    ResponseEntity<?> review(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String id,
            @PathVariable String resource) {
        return reply(
                200, answers.proxy(authorization, id, "/" + resource, "GET", null, null, null));
    }

    @PostMapping("/api/rag/harness/answers/{id}/{operation:pause|cancel|resume}")
    ResponseEntity<?> control(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String id,
            @PathVariable String operation,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String etag,
            @RequestBody JsonNode body) {
        return reply(
                202, answers.proxy(authorization, id, "/" + operation, "POST", body, key, etag));
    }

    @PostMapping("/api/rag/harness/answers/{id}/approvals/{approval}/decision")
    ResponseEntity<?> decision(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String id,
            @PathVariable String approval,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String etag,
            @RequestBody JsonNode body) {
        if (!approval.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("HARNESS_APPROVAL_INVALID");
        return reply(
                202,
                answers.proxy(
                        authorization,
                        id,
                        "/approvals/" + approval + "/decision",
                        "POST",
                        body,
                        key,
                        etag));
    }

    @PostMapping("/internal/rag/harness/answer-access")
    ResponseEntity<?> access(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody Access request) {
        return access(authorization, request, false);
    }

    @PostMapping("/internal/rag/harness/output-answer-access")
    ResponseEntity<?> outputAccess(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody Access request) {
        return access(authorization, request, true);
    }

    private ResponseEntity<?> access(String authorization, Access request, boolean output) {
        boolean allowed;
        try {
            var actor = actors.verify(authorization, output ? "rag-output" : "rag");
            allowed =
                    output
                            ? answers.outputAnswerAccess(
                                    actor,
                                    authorization,
                                    request.contextId(),
                                    request.promptSha256())
                            : answers.answerAccess(
                                    actor,
                                    authorization,
                                    request.contextId(),
                                    request.promptSha256());
        } catch (RuntimeException denied) {
            allowed = false;
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(ApiResponse.success(Map.of("allowed", allowed)));
    }

    static ResponseEntity<?> response(HarnessAnswerService.Result result) {
        var builder = ResponseEntity.status(result.status()).header("Cache-Control", "no-store");
        if (result.etag() != null) builder.header("ETag", result.etag());
        if (result.status() == 202) builder.header("Retry-After", "1");
        return builder.body(ApiResponse.success(result.body()));
    }

    private ResponseEntity<?> reply(int status, HarnessPlatformClient.Reply reply) {
        return response(new HarnessAnswerService.Result(status, reply.body(), reply.etag()));
    }

    /**
     * @author heyu
     */
    public record Access(
            @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String contextId,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String promptSha256) {}
}
