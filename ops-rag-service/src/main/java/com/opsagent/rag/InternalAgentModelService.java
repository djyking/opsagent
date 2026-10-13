package com.opsagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 集中使用现有 AI 密钥、额度和用量审计，原生模型步受能力验证和持久化去重控制。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class InternalAgentModelService {
    private static final Logger LOG = LoggerFactory.getLogger(InternalAgentModelService.class);
    private final AiProperties properties;
    private final NativeToolModelClient model;
    private final InternalAgentStore store;
    private final AiBudgetGuard budget;
    private final AiUsageRepository usage;
    private final MeterRegistry metrics;
    private final ObjectMapper mapper;

    InternalAgentModelService(
            AiProperties properties,
            NativeToolModelClient model,
            InternalAgentStore store,
            AiBudgetGuard budget,
            AiUsageRepository usage,
            MeterRegistry metrics,
            ObjectMapper mapper) {
        this.properties = properties;
        this.model = model;
        this.store = store;
        this.budget = budget;
        this.usage = usage;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    InternalAgentDtos.Models models() {
        return new InternalAgentDtos.Models(
                AiProperties.SUPPORTED.stream().map(this::capability).toList());
    }

    InternalAgentDtos.ModelCapability probe(String provider, InternalActorTokens.Context actor) {
        if (!actor.roles().contains("ADMIN"))
            throw new BusinessException(ErrorCode.FORBIDDEN, "ADMIN_REQUIRED");
        var settings = settings(provider, null);
        String nonce = UUID.randomUUID().toString();
        String name = "opsagent_capability_probe";
        var tool =
                Map.<String, Object>of(
                        "type",
                        "function",
                        "function",
                        Map.of(
                                "name",
                                name,
                                "description",
                                "Return the supplied nonce for protocol verification; no tool is"
                                        + " executed.",
                                "parameters",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "nonce",
                                                Map.of("type", "string", "enum", List.of(nonce))),
                                        "required",
                                        List.of("nonce"),
                                        "additionalProperties",
                                        false)));
        var request =
                new InternalAgentDtos.TurnRequest(
                        "probe:" + UUID.randomUUID(),
                        provider,
                        settings.getModel(),
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "Call " + name + " with nonce " + nonce)),
                        List.of(tool),
                        256,
                        4096);
        budget.checkRequestRate();
        String status = "UNAVAILABLE";
        long started = System.nanoTime();
        try (var permit = budget.acquire()) {
            InternalAgentDtos.TurnResponse result = model.call(request, name);
            var tree = mapper.valueToTree(result.assistantMessage());
            var call = tree.path("tool_calls").path(0);
            boolean valid =
                    "TOOL_CALLS".equals(result.outcome())
                            && tree.path("tool_calls").size() == 1
                            && name.equals(call.path("function").path("name").asText());
            if (valid) {
                valid =
                        nonce.equals(
                                mapper.readTree(call.path("function").path("arguments").asText())
                                        .path("nonce")
                                        .asText());
            }
            status = valid ? "VERIFIED" : "UNSUPPORTED";
            audit(
                    request,
                    actor,
                    result,
                    valid ? null : "TOOL_CAPABILITY_UNSUPPORTED",
                    elapsed(started));
        } catch (AiProviderException exception) {
            status =
                    exception.statusCode() == 400 || exception.statusCode() == 422
                            ? "UNSUPPORTED"
                            : "UNAVAILABLE";
            audit(request, actor, null, "TOOL_CAPABILITY_" + status, elapsed(started));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            status = "UNSUPPORTED";
            audit(request, actor, null, "INVALID_NATIVE_TOOL_RESPONSE", elapsed(started));
        }
        store.capability(provider, settings, status);
        return capability(provider);
    }

    InternalAgentDtos.TurnResponse turn(
            InternalAgentDtos.TurnRequest request, InternalActorTokens.Context actor) {
        try {
            var settings = settings(request.provider(), request.model());
            model.validate(request);
            if (!store.capability(request.provider(), settings, true).toolCalling()) {
                throw new BusinessException(ErrorCode.CONFLICT, "MODEL_TOOL_CALLING_NOT_VERIFIED");
            }
            var replay = store.claim(request, actor);
            if (replay != null) return replay;
        } catch (BusinessException failure) {
            throw InternalAgentModelFailure.exception(failure.getMessage());
        }
        long started = System.nanoTime();
        // Every attempt shares the configured decision deadline and the existing run/actor lease.
        // Leave a small interval for the durable receipt to reach the caller before lease expiry.
        long decisionLimit =
                Math.min(
                        TimeUnit.SECONDS.toNanos(
                                Math.max(3, Math.min(properties.getTimeoutSeconds(), 120))),
                        Duration.between(Instant.now(), actor.validUntil()).toNanos()
                                - TimeUnit.SECONDS.toNanos(2));
        int reservation = model.reservation(request);
        int unknownReservation = 0;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long attemptStarted = System.nanoTime();
            long remaining = decisionLimit - (attemptStarted - started);
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                String code = remaining <= 0 ? "MODEL_TIMEOUT" : "MODEL_CANCELLED";
                store.failed(request.callId(), attempt > 1, code);
                throw InternalAgentModelFailure.exception(code);
            }
            InternalAgentDtos.TurnResponse result;
            boolean submitted = false;
            try (var permit = budget.acquire()) {
                // One outbound attempt has an absolute body-inclusive timeout. The second shares
                // this deadline.
                remaining = decisionLimit - (System.nanoTime() - started);
                if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                    throw new AiProviderException(
                            request.provider(),
                            0,
                            "模型准入后已到期或中断",
                            null,
                            remaining <= 0
                                    ? AiProviderException.FailureKind.TIMEOUT
                                    : AiProviderException.FailureKind.CANCELLED);
                }
                store.beginAttempt(request.callId(), attempt, request.provider(), reservation);
                submitted = true;
                result = model.call(request, null, Duration.ofNanos(remaining));
            } catch (AiProviderException failure) {
                String code = InternalAgentModelFailure.code(failure);
                if (submitted) finishAttemptSafely(request.callId(), attempt, null, code);
                auditSafely(request, actor, null, code, elapsed(attemptStarted));
                LOG.warn(
                        "Agent model attempt failed: call={}, provider={}, attempt={}, kind={},"
                                + " http={}, elapsedMs={}, transport={}, responseStarted={}",
                        InternalAgentStore.hash(request.callId()),
                        request.provider(),
                        attempt,
                        failure.kind(),
                        failure.statusCode(),
                        elapsed(attemptStarted),
                        failure.diagnosticCode(),
                        failure.responseStarted());
                boolean retry =
                        attempt == 1
                                && InternalAgentModelFailure.transientFailure(failure)
                                && !Thread.currentThread().isInterrupted()
                                && 2L * reservation <= request.remainingTokens()
                                && System.nanoTime() - started
                                        < decisionLimit - TimeUnit.SECONDS.toNanos(1);
                if (retry) {
                    unknownReservation = reservation;
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        store.failed(request.callId(), true, "MODEL_CANCELLED");
                        throw InternalAgentModelFailure.exception("MODEL_CANCELLED");
                    }
                    continue;
                }
                store.failed(request.callId(), submitted || attempt > 1, code);
                throw InternalAgentModelFailure.exception(code);
            } catch (RuntimeException failure) {
                String code = submitted ? "MODEL_RESPONSE_INVALID" : "MODEL_BUDGET_REJECTED";
                if (submitted) finishAttemptSafely(request.callId(), attempt, null, code);
                store.failed(request.callId(), submitted || attempt > 1, code);
                auditSafely(request, actor, null, code, elapsed(attemptStarted));
                LOG.warn(
                        "Agent model admission/protocol failed: call={}, attempt={}, code={},"
                                + " type={}",
                        InternalAgentStore.hash(request.callId()),
                        attempt,
                        code,
                        failure.getClass().getSimpleName());
                throw InternalAgentModelFailure.exception(code);
            }
            finishAttemptSafely(
                    request.callId(),
                    attempt,
                    result,
                    result.usageKnown() ? null : "USAGE_UNKNOWN");
            long estimated =
                    (long) unknownReservation
                            + (result.usageKnown() ? result.totalTokens() : reservation);
            int charged = (int) Math.min(request.remainingTokens(), Math.max(estimated, 1L));
            var receipt =
                    new InternalAgentDtos.TurnResponse(
                            result.outcome(),
                            result.assistantMessage(),
                            result.inputTokens(),
                            result.outputTokens(),
                            result.totalTokens(),
                            unknownReservation == 0 && result.usageKnown(),
                            result.provider(),
                            result.model(),
                            result.finishReason(),
                            elapsed(started),
                            charged,
                            attempt);
            // Never retry the provider after it returned: persistence and audit are different
            // failure boundaries.
            try {
                store.complete(request.callId(), receipt);
            } catch (RuntimeException failure) {
                store.failed(request.callId(), true, "MODEL_RECEIPT_FAILED");
                auditSafely(
                        request, actor, result, "MODEL_RECEIPT_FAILED", elapsed(attemptStarted));
                throw InternalAgentModelFailure.exception("MODEL_RECEIPT_FAILED");
            }
            auditSafely(
                    request,
                    actor,
                    result,
                    result.usageKnown() ? null : "USAGE_UNKNOWN",
                    elapsed(attemptStarted));
            return receipt;
        }
        throw new IllegalStateException("模型尝试次数超出上限");
    }

    private void auditSafely(
            InternalAgentDtos.TurnRequest request,
            InternalActorTokens.Context actor,
            InternalAgentDtos.TurnResponse result,
            String error,
            long elapsed) {
        try {
            audit(request, actor, result, error, elapsed);
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Agent model audit unavailable: call={}, type={}",
                    InternalAgentStore.hash(request.callId()),
                    failure.getClass().getSimpleName());
        }
    }

    private void finishAttemptSafely(
            String callId, int attempt, InternalAgentDtos.TurnResponse result, String error) {
        try {
            store.finishAttempt(callId, attempt, result, error);
        } catch (RuntimeException unavailable) {
            LOG.warn(
                    "Agent attempt usage receipt unavailable: call={}, attempt={}, type={}",
                    InternalAgentStore.hash(callId),
                    attempt,
                    unavailable.getClass().getSimpleName());
        }
    }

    private InternalAgentDtos.ModelCapability capability(String provider) {
        var settings = properties.settings(provider);
        return store.capability(
                provider, settings, properties.isEnabled() && settings.selectable());
    }

    private AiProperties.ProviderSettings settings(String provider, String expectedModel) {
        if (!AiProperties.SUPPORTED.contains(provider)) {
            throw new BusinessException(ErrorCode.VALIDATION, "MODEL_PROVIDER_UNSUPPORTED");
        }
        var settings = properties.settings(provider);
        if (!properties.isEnabled() || !settings.selectable()) {
            throw new BusinessException(ErrorCode.CONFLICT, "MODEL_NOT_CONFIGURED");
        }
        if (expectedModel != null && !settings.getModel().equals(expectedModel)) {
            throw new BusinessException(ErrorCode.CONFLICT, "MODEL_SNAPSHOT_CHANGED");
        }
        return settings;
    }

    private void audit(
            InternalAgentDtos.TurnRequest request,
            InternalActorTokens.Context actor,
            InternalAgentDtos.TurnResponse result,
            String error,
            long elapsed) {
        String outcome = result == null ? "FAILED" : result.outcome();
        usage.save(
                new AiUsageRepository.AiUsage(
                        MDC.get("traceId"),
                        actor.userId(),
                        request.provider(),
                        result == null ? request.model() : result.model(),
                        InternalAgentStore.hash(request.callId()),
                        result == null || result.inputTokens() == null ? 0 : result.inputTokens(),
                        result == null || result.outputTokens() == null ? 0 : result.outputTokens(),
                        elapsed,
                        List.of("FINAL", "TOOL_CALLS").contains(outcome),
                        error));
        metrics.counter(
                        "opsagent_agent_model_requests",
                        "provider",
                        request.provider(),
                        "outcome",
                        outcome)
                .increment();
        metrics.timer("opsagent_agent_model_duration", "provider", request.provider())
                .record(elapsed, TimeUnit.MILLISECONDS);
    }

    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
