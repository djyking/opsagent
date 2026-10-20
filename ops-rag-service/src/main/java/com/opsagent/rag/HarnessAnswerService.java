package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Ops owns retrieval and citation semantics; the platform owns durable model execution. No
 * model/provider fallback is performed when a request has a persisted owner.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
@ConditionalOnProperty(name = "ops.harness.answer.enabled", havingValue = "true")
public class HarnessAnswerService {
    private final HarnessAnswerRepository repository;
    private final HarnessPlatformClient client;
    private final RagService rag;
    private final HarnessCitationAccessController citations;
    private final ObjectMapper json;
    private final String project;
    private final String application;
    private final String newOwner;
    private final JsonNode release;
    private final String provider;
    private final String model;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObservabilityEvidenceClient observations;

    public HarnessAnswerService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            RagService rag,
            HarnessCitationAccessController citations,
            @Value("${ops.harness.auth-origin}") String authOrigin,
            @Value("${ops.harness.platform-origin}") String platformOrigin,
            @Value("${ops.harness.application-credential}") String credential,
            @Value("${ops.harness.application-id}") String application,
            @Value("${ops.harness.project}") String project,
            @Value("${ops.harness.answer.new-owner:LEGACY}") String newOwner,
            @Value("${ops.harness.answer.release-id:}") String releaseId,
            @Value("${ops.harness.answer.release-digest:}") String releaseDigest,
            @Value("${ops.harness.answer.provider:deepseek}") String provider,
            @Value("${ops.harness.answer.model:platform-managed}") String model) {
        this.repository = new HarnessAnswerRepository(jdbc);
        this.json = json;
        this.rag = rag;
        this.citations = citations;
        this.client = new HarnessPlatformClient(json, authOrigin, platformOrigin, credential);
        if (!project.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || !application.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || !Set.of("LEGACY", "HARNESS").contains(newOwner))
            throw new IllegalArgumentException("HARNESS_ANSWER_CONFIG_INVALID");
        if ("HARNESS".equals(newOwner)
                && (!releaseId.matches("[a-f0-9-]{36}")
                        || !releaseDigest.matches("sha256:[a-f0-9]{64}")))
            throw new IllegalArgumentException("HARNESS_ANSWER_RELEASE_REQUIRED");
        this.application = application;
        this.project = project;
        this.newOwner = newOwner;
        this.provider = provider;
        this.model = model;
        this.release =
                json.valueToTree(
                        Map.of(
                                "agentId",
                                "ops-rag-answer",
                                "releaseId",
                                releaseId,
                                "digest",
                                releaseDigest));
    }

    Result submit(
            String authorization,
            String key,
            RagController.ChatRequest request,
            Supplier<RagService.Answer> legacy) {
        String token = login(authorization);
        current(token, "runs:create");
        String selectedOwner = rag.legacyDomainRequired(request) ? "LEGACY" : newOwner;
        var row =
                repository.assign(
                        application,
                        project,
                        SecurityUsers.current().userId(),
                        key,
                        encode(request),
                        selectedOwner);
        if ("NEW".equals(row.state()) && repository.claim(row)) {
            try {
                if ("LEGACY".equals(row.owner())) {
                    // One attempt only. A JVM crash or lost provider reply leaves an UNKNOWN owner.
                    RagService.Answer answer = legacy.get();
                    current(token, "runs:output:read");
                    repository.immediate(row.id(), encode(answer.references()), encode(answer));
                    return new Result(200, answer, null);
                }
                if (request.provider() != null
                        && !request.provider().isBlank()
                        && !request.provider().equals(provider))
                    throw failure(ErrorCode.VALIDATION, "HARNESS_PROVIDER_BOUND_TO_RELEASE");
                var plan = rag.prepareHarness(request);
                if (plan.immediate() != null) {
                    current(token, "runs:output:read");
                    repository.immediate(
                            row.id(),
                            encode(plan.immediate().references()),
                            encode(
                                    plan.immediate()
                                            .withPresentation(
                                                    AnswerStyle.orDefault(request.answerStyle()),
                                                    null)));
                    return new Result(200, plan.immediate(), null);
                }
                requireSupportedSources(plan.sources());
                String prompt =
                        "OpsAgent 问答规则：\n"
                                + plan.request().systemPrompt()
                                + "\n\nOpsAgent 当前请求及授权证据：\n"
                                + plan.request().userPrompt();
                if (prompt.length() > 100000)
                    throw failure(ErrorCode.VALIDATION, "HARNESS_PROMPT_TOO_LARGE");
                var dispatch = json.createObjectNode();
                dispatch.set("releaseRef", release);
                dispatch.putObject("inputs").put("prompt", prompt).put("contextId", row.id());
                dispatch.put("clientReference", "ops-rag:" + row.id());
                String frozenDispatch = encode(dispatch);
                if (frozenDispatch.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
                    throw failure(ErrorCode.VALIDATION, "HARNESS_DISPATCH_TOO_LARGE");
                repository.prepared(
                        row.id(),
                        encode(new StoredContext(plan, provider, model)),
                        HarnessRequestRoutes.hash(prompt),
                        frozenDispatch);
            } catch (RuntimeException failure) {
                if ("LEGACY".equals(row.owner())) repository.unknown(row.id());
                else repository.rejected(row.id());
                throw failure;
            }
        }
        row = repository.get(row.id());
        if ("READY".equals(row.state())) {
            // Retrying uses exactly the stored release, input and platform idempotency key.
            current(token, "runs:create");
            var accepted =
                    client.call(
                                    base() + "/runs",
                                    "POST",
                                    read(row.dispatch()),
                                    token,
                                    row.id(),
                                    null)
                            .body();
            repository.bind(row.id(), accepted.path("run").path("id").asText());
        }
        return status(authorization, row.id());
    }

    Result status(String authorization, String id) {
        String token = login(authorization);
        current(token, "runs:read");
        var row = owned(id);
        if ("ARCHIVED".equals(row.state()))
            throw failure(ErrorCode.NOT_FOUND, "HARNESS_PAYLOAD_ARCHIVED");
        if ("COMPLETED".equals(row.state())) {
            current(token, "runs:output:read");
            var answer = convert(row.answer(), RagService.Answer.class);
            verifyStoredSources(row, token, answer.references());
            return new Result(200, answer, null);
        }
        if (row.runId() == null) {
            return new Result(
                    202,
                    Map.of(
                            "owner",
                            row.owner(),
                            "routeId",
                            row.id(),
                            "status",
                            row.state(),
                            "statusUrl",
                            statusUrl(row.id()),
                            "retryPolicy",
                            "SAME_REQUEST_KEY_ONLY_NO_EXECUTOR_FALLBACK"),
                    null);
        }
        var reply = client.call(base() + "/runs/" + row.runId(), "GET", null, token, null, null);
        var run = reply.body();
        repository.observedTerminal(row.id(), run.path("status").asText());
        if ("COMPLETED".equals(run.path("status").asText())) {
            if (!"AVAILABLE".equals(run.path("output").path("visibility").asText())
                    || !run.path("output").path("value").isTextual())
                throw failure(ErrorCode.FORBIDDEN, "HARNESS_ANSWER_ACCESS_REVOKED");
            var stored = convert(row.context(), StoredContext.class);
            var plan = stored.plan();
            var original = convert(row.request(), RagController.ChatRequest.class);
            long charged = run.path("usage").path("chargedTokens").asLong();
            LlmResult generated =
                    new LlmResult(
                            run.path("output").path("value").asText(),
                            stored.provider(),
                            stored.model(),
                            0,
                            0,
                            true,
                            "stop",
                            0,
                            0,
                            (int) Math.min(Integer.MAX_VALUE, charged),
                            false,
                            1);
            var answer =
                    rag.completeHarness(
                            plan,
                            generated,
                            Math.max(
                                    0,
                                    (repository.get(row.id()).completedEpoch() - row.createdEpoch())
                                            * 1000),
                            original.answerStyle());
            return new Result(200, answer, reply.etag());
        }
        return new Result(
                202,
                Map.of(
                        "owner",
                        row.owner(),
                        "routeId",
                        row.id(),
                        "status",
                        run.path("status").asText(),
                        "run",
                        run,
                        "statusUrl",
                        statusUrl(row.id())),
                reply.etag());
    }

    HarnessPlatformClient.Reply proxy(
            String authorization,
            String id,
            String suffix,
            String method,
            JsonNode body,
            String key,
            String etag) {
        String token = login(authorization);
        current(token, suffix.startsWith("/approval") ? "approvals:read" : "runs:read");
        var row = suffix.startsWith("/approval") ? repository.get(id) : owned(id);
        if (!row.application().equals(application) || !row.project().equals(project))
            throw failure(ErrorCode.NOT_FOUND, "HARNESS_ANSWER_NOT_FOUND");
        if (row.runId() == null || !"HARNESS".equals(row.owner()))
            throw failure(ErrorCode.CONFLICT, "HARNESS_RUN_NOT_SUBMITTED");
        return client.call(
                base() + "/runs/" + row.runId() + suffix, method, body, token, key, etag);
    }

    boolean answerAccess(
            InternalActorTokens.Context actor,
            String authorization,
            String contextId,
            String promptHash) {
        try {
            var row = repository.get(contextId);
            if (!application.equals(row.application())
                    || !project.equals(row.project())
                    || row.user() != actor.userId()
                    || !actor.runId().equals(row.runId())
                    || !"HARNESS".equals(row.owner())
                    || !promptHash.equals(row.promptHash())
                    || !"SUBMITTED".equals(row.state())) return false;
            var stored = convert(row.context(), StoredContext.class);
            var plan = stored.plan();
            try (var scope = InternalActorAccess.open(actor)) {
                return sourcesAllowed(
                        authorization,
                        plan.sources(),
                        convert(row.request(), RagController.ChatRequest.class));
            }
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private void verifyStoredSources(
            HarnessAnswerRepository.Row row, String token, List<RagService.Source> sources) {
        if (sources.isEmpty()) return;
        String authorization =
                client.domainAuthorization(
                        project, token, "ops-answer-" + row.id(), row.createdEpoch() + 86400);
        if (!sourcesAllowed(
                authorization, sources, convert(row.request(), RagController.ChatRequest.class)))
            throw failure(ErrorCode.FORBIDDEN, "HARNESS_ANSWER_ACCESS_REVOKED");
    }

    private boolean sourcesAllowed(
            String authorization,
            List<RagService.Source> sources,
            RagController.ChatRequest original) {
        requireSupportedSources(sources);
        if (sources.isEmpty()) return true;
        var evidence =
                sources.stream()
                        .filter(source -> "KNOWLEDGE_DOCUMENT".equals(source.sourceType()))
                        .map(
                                source ->
                                        new HarnessCitationAccessController.Citation(
                                                source.documentId(),
                                                source.chunkId(),
                                                source.version()))
                        .toList();
        if (!evidence.isEmpty()) {
            var verdict =
                    citations.validate(
                            authorization,
                            new HarnessCitationAccessController.Request(
                                    evidence,
                                    original.documentId() != null || original.ticketId() != null));
            if (verdict.code() != 0 || verdict.data() == null || !verdict.data().allowed())
                return false;
        }
        var observed =
                sources.stream()
                        .filter(source -> "OBSERVABILITY_EVIDENCE".equals(source.sourceType()))
                        .toList();
        if (!observed.isEmpty()) {
            if (observations == null || original.observabilityContext() == null) return false;
            var groups =
                    observed.stream()
                            .collect(
                                    java.util.stream.Collectors.groupingBy(
                                            RagService.Source::evidenceBundleId));
            for (var group : groups.entrySet()) {
                var range = original.observabilityContext();
                var current =
                        observations.load(
                                new ObservabilityContext(
                                        range.service(),
                                        range.environment(),
                                        range.timeRange(),
                                        group.getKey()));
                if (!current.available() || !group.getKey().equals(current.evidenceBundleId()))
                    return false;
                var ids =
                        current.entries().stream()
                                .map(ObservabilityEvidenceClient.Entry::id)
                                .collect(java.util.stream.Collectors.toSet());
                if (group.getValue().stream()
                        .anyMatch(source -> !ids.contains(source.evidenceId()))) return false;
            }
        }
        return true;
    }

    private void requireSupportedSources(List<RagService.Source> sources) {
        if (sources == null
                || sources.size() > 100
                || sources.stream().anyMatch(source -> !supported(source)))
            throw failure(ErrorCode.FORBIDDEN, "HARNESS_SOURCE_PROJECTION_UNAVAILABLE");
    }

    private boolean supported(RagService.Source source) {
        if (source == null || source.sourceType() == null) return false;
        return switch (source.sourceType()) {
            case "KNOWLEDGE_DOCUMENT" ->
                    source.documentId() > 0
                            && source.chunkId() > 0
                            && source.version() != null
                            && source.version() > 0;
            case "OFFICIAL_WEB" ->
                    OfficialDocumentationClient.CATALOG.values().stream()
                            .anyMatch(official -> official.url().equals(source.sourceUrl()));
            case "CMDB" -> "/itsm/cmdb".equals(source.sourceUrl());
            case "OBSERVABILITY_EVIDENCE" ->
                    source.evidenceBundleId() != null
                            && source.evidenceBundleId().matches("[a-fA-F0-9-]{36}")
                            && source.evidenceId() != null
                            && !source.evidenceId().isBlank();
            default -> false;
        };
    }

    private HarnessAnswerRepository.Row owned(String id) {
        var row = repository.get(id);
        if (row.user() != SecurityUsers.current().userId()
                || !row.application().equals(application)
                || !row.project().equals(project))
            throw failure(ErrorCode.NOT_FOUND, "HARNESS_ANSWER_NOT_FOUND");
        return row;
    }

    private void current(String token, String permission) {
        var identity = client.identity(project, token);
        if (!identity.path("applicationId").asText().equals(application)
                || !identity.path("projectId").asText().equals(project)
                || !identity.path("subject")
                        .asText()
                        .equals(Long.toString(SecurityUsers.current().userId())))
            throw failure(ErrorCode.FORBIDDEN, "HARNESS_SUBJECT_MISMATCH");
        var permissions = new java.util.HashSet<String>();
        identity.path("permissions").forEach(value -> permissions.add(value.asText()));
        if (!permissions.contains(permission)
                || !permission.startsWith("approvals:")
                        && !permissions.contains("opsagent:rag:search"))
            throw failure(ErrorCode.FORBIDDEN, "HARNESS_PERMISSION_REVOKED");
    }

    private String base() {
        return "/v1/projects/" + project;
    }

    private String statusUrl(String id) {
        return "/api/rag/harness/answers/" + id;
    }

    private String login(String authorization) {
        if (authorization == null
                || !authorization.startsWith("Bearer ")
                || authorization.length() > 10000)
            throw failure(ErrorCode.UNAUTHENTICATED, "HARNESS_LOGIN_REQUIRED");
        return authorization.substring(7);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw failure(ErrorCode.SYSTEM_ERROR, "HARNESS_CONTEXT_INVALID");
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception failure) {
            throw failure(ErrorCode.SYSTEM_ERROR, "HARNESS_CONTEXT_INVALID");
        }
    }

    private <T> T convert(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception failure) {
            throw failure(ErrorCode.SYSTEM_ERROR, "HARNESS_CONTEXT_INVALID");
        }
    }

    private static BusinessException failure(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }

    /**
     * @author heyu
     */
    record Result(int status, Object body, String etag) {}

    /**
     * @author heyu
     */
    record StoredContext(RagService.StreamPlan plan, String provider, String model) {}
}
