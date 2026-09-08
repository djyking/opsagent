package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 人工触发真实演练、固定探针与隔离恢复；不按周期制造故障或自动产生模型费用。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class DemoTargetService {
    private final DemoTargetRepository repository;
    private final DemoTargetClient target;
    private final ObjectMapper json;
    private final DemoTargetEvidenceRepository evidence;
    private final Map<String, ProbeState> probes = new java.util.HashMap<>();

    DemoTargetService(
            DemoTargetRepository repository,
            DemoTargetClient target,
            ObjectMapper json,
            MeterRegistry metrics,
            DemoTargetEvidenceRepository evidence) {
        this.repository = repository;
        this.target = target;
        this.json = json;
        this.evidence = evidence;
        for (String targetCode : DemoTargetDtos.TARGETS) {
            ProbeState probe = new ProbeState();
            probes.put(targetCode, probe);
            Gauge.builder("opsagent.demo.probe.success", probe.success, AtomicLong::doubleValue)
                    .tag("service", targetCode)
                    .register(metrics);
            Gauge.builder(
                            "opsagent.demo.probe.timestamp.seconds",
                            probe.observedEpoch,
                            AtomicLong::doubleValue)
                    .tag("service", targetCode)
                    .register(metrics);
        }
    }

    synchronized DemoTargetDtos.Incident create(
            DemoTargetDtos.CreateScenario request, InternalActorTokens.Context actor) {
        requireTarget(actor);
        requireOperator(actor);
        if (!DemoTargetDtos.scenarioTarget(request.scenarioCode()).equals(actor.targetCode())
                || request.ttlSeconds() != null
                        && (request.ttlSeconds() < 180 || request.ttlSeconds() > 900)) {
            throw new BusinessException(ErrorCode.VALIDATION, "INVALID_DEMO_SCENARIO");
        }
        ObjectNode snapshot = capture(actor.targetCode());
        if (snapshot.path("business").path("httpStatus").asInt() != 200
                || !"BASELINE".equals(snapshot.path("status").asText())) {
            throw new BusinessException(ErrorCode.CONFLICT, "DEMO_BASELINE_NOT_HEALTHY");
        }
        var incident = repository.create(request, actor);
        try {
            target.inject(incident);
            capture(actor.targetCode());
        } catch (RuntimeException exception) {
            repository.injectionUncertain(incident.incidentId());
            throw exception;
        }
        return repository.get(incident.incidentId());
    }

    synchronized ObjectNode action(
            DemoTargetDtos.Action request, InternalActorTokens.Context actor) {
        requireTarget(actor);
        requireOperator(actor);
        var incident = authorized(request.incidentId(), actor);
        String allowedAction = DemoTargetDtos.recoveryAction(incident.scenarioCode());
        if (!allowedAction.equals(request.action())) {
            throw new BusinessException(ErrorCode.VALIDATION, "ACTION_SCENARIO_MISMATCH");
        }
        Map<String, Object> saved = repository.reserveAction(request, actor);
        if (saved.get("result_json") != null) {
            try {
                return (ObjectNode) json.readTree(String.valueOf(saved.get("result_json")));
            } catch (Exception exception) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "ACTION_RECORD_INVALID");
            }
        }
        // A process crash can leave REQUESTED. Fixed restore is safe to resume only for the same
        // revision/incident.
        ObjectNode before = capture(actor.targetCode());
        if (!incident.incidentId().equals(before.path("incidentId").asText())) {
            throw new BusinessException(ErrorCode.CONFLICT, "INCIDENT_MISMATCH");
        }
        if ("FAULT_ACTIVE".equals(before.path("status").asText())
                && !request.expectedRevision().equals(before.path("appliedRevision").asText())) {
            throw new BusinessException(ErrorCode.CONFLICT, "REVISION_CONFLICT");
        }
        try {
            target.restore(
                    actor.targetCode(),
                    request,
                    actor.runId().startsWith("manual-") ? "MANUAL" : "AGENT_TOOL");
            ObjectNode after = capture(actor.targetCode());
            boolean verified =
                    after.path("business").path("httpStatus").asInt() == 200
                            && "BASELINE".equals(after.path("status").asText())
                            && "APPLIED".equals(after.path("configurationStatus").asText());
            after.put("actionAccepted", true);
            after.put("recoveryVerified", verified);
            after.put(
                    "agentRecovered",
                    verified && "AGENT_TOOL".equals(after.path("recoverySource").asText()));
            repository.actionResult(
                    request.idempotencyKey(),
                    verified ? "VERIFIED" : "VERIFYING",
                    after.toString());
            return after;
        } catch (RuntimeException exception) {
            repository.actionResult(request.idempotencyKey(), "UNCONFIRMED", null);
            throw exception;
        }
    }

    DemoTargetDtos.Incident authorized(String id, InternalActorTokens.Context actor) {
        requireTarget(actor);
        var incident = repository.get(id);
        if (!actor.targetCode().equals(incident.targetCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "DEMO_TARGET_MISMATCH");
        }
        if (incident.ownerId() != actor.userId() && !admin(actor)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "DEMO_INCIDENT_NOT_OWNED");
        }
        return incident;
    }

    List<DemoTargetDtos.Incident> list(InternalActorTokens.Context actor) {
        requireTarget(actor);
        return repository.page(actor.userId(), admin(actor), actor.targetCode());
    }

    synchronized ObjectNode snapshot(InternalActorTokens.Context actor) {
        requireTarget(actor);
        String targetCode = actor.targetCode();
        ObjectNode result = capture(targetCode);
        result.put("configured", target.configured());
        result.put("scope", "ISOLATED_DEMO");
        result.put("targetCode", targetCode);
        Instant available = repository.availableAfter(targetCode);
        result.put("nextAvailableAt", available == null ? null : available.toString());
        result.put(
                "canStart",
                target.configured()
                        && result.path("business").path("httpStatus").asInt() == 200
                        && "BASELINE".equals(result.path("status").asText())
                        && "APPLIED".equals(result.path("configurationStatus").asText())
                        && repository.active(targetCode) == null
                        && !repository.configurationBusy(targetCode)
                        && (available == null || !available.isAfter(Instant.now())));
        String incidentId = result.path("incidentId").asText();
        if (!incidentId.isBlank()) {
            var incident = repository.get(incidentId);
            result.put("ownedByCurrentActor", incident.ownerId() == actor.userId());
        }
        return result;
    }

    synchronized ObjectNode evidence(String id, InternalActorTokens.Context actor) {
        var incident = authorized(id, actor);
        ObjectNode currentSnapshot = null;
        boolean current = false;
        String captureStatus = "HISTORICAL";
        try {
            // A recorded recovery does not replace the current technical verification.
            // capture() keeps completed-incident observations immutable, while the live
            // snapshot remains usable only while the runtime retains this exact incident ID.
            ObjectNode captured = capture(incident.targetCode());
            incident = authorized(id, actor);
            current = id.equals(captured.path("incidentId").asText());
            if (current) currentSnapshot = evidence.safeSnapshot(captured);
            captureStatus = current ? "LIVE_OBSERVATION" : "HISTORICAL";
        } catch (BusinessException unavailable) {
            captureStatus = "LIVE_TARGET_UNAVAILABLE";
        }
        ObjectNode result =
                json.createObjectNode()
                        .put("incidentId", id)
                        .put("targetCode", incident.targetCode())
                        .put("current", current)
                        .put("captureStatus", captureStatus)
                        .put("causality", "NOT_ESTABLISHED")
                        .put("explanation", "时间相近的配置变更仅为排查线索；根因需结合真实业务观测与恢复验证。");
        ObjectNode incidentView = json.valueToTree(incident);
        incidentView.remove("scenarioCode");
        result.set("incident", incidentView);
        result.set(
                "snapshot",
                currentSnapshot == null ? json.getNodeFactory().nullNode() : currentSnapshot);
        result.set("observations", json.valueToTree(evidence.observations(incident)));
        result.set("changes", json.valueToTree(evidence.changes(incident)));
        return result;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 20000)
    synchronized void scheduledProbe() {
        if (!target.configured()) return;
        for (String targetCode : DemoTargetDtos.TARGETS) {
            try {
                capture(targetCode);
            } catch (RuntimeException ignored) {
                probes.get(targetCode).success.set(-1);
            }
        }
    }

    private ObjectNode capture(String targetCode) {
        ObjectNode snapshot = (ObjectNode) target.snapshot(targetCode);
        JsonNode business = target.preview(targetCode);
        ObjectNode afterProbe = (ObjectNode) target.snapshot(targetCode);
        if (!snapshot.path("appliedRevision")
                .asText()
                .equals(afterProbe.path("appliedRevision").asText())) {
            throw new BusinessException(ErrorCode.CONFLICT, "PROBE_CONFIGURATION_CHANGED");
        }
        snapshot = afterProbe;
        snapshot.set("business", business);
        snapshot.put("expectedRevision", snapshot.path("appliedRevision").asText());
        Instant observed = Instant.now();
        int status = business.path("httpStatus").asInt();
        ProbeState probe = probes.get(targetCode);
        probe.consecutiveSuccesses =
                status == 200 ? Math.min(1000, probe.consecutiveSuccesses + 1) : 0;
        ((ObjectNode) business).put("consecutiveSuccesses", probe.consecutiveSuccesses);
        snapshot.put("observedAt", observed.toString());
        probe.success.set(status == 200 ? 1 : 0);
        probe.observedEpoch.set(observed.getEpochSecond());
        String id = snapshot.path("incidentId").asText();
        var active = repository.active(targetCode);
        if (!id.isBlank()) {
            if (active != null && active.incidentId().equals(id)) {
                repository.observed(
                        id,
                        snapshot.path("appliedRevision").asText(),
                        status,
                        business.path("reasonCode").asText(),
                        snapshot.path("recoverySource").asText(),
                        observed);
            }
        }
        if (active != null
                && !active.incidentId().equals(id)
                && status == 200
                && "BASELINE".equals(snapshot.path("status").asText())
                && active.expiresAt().isBefore(observed)) {
            repository.releaseExpiredUnapplied(active.incidentId(), observed);
        }
        ObjectNode evidenceSnapshot = snapshot.deepCopy();
        if (active == null || !active.incidentId().equals(id)) {
            // A baseline runtime retains its last incident ID for recovery provenance. Do not
            // attach subsequent days of healthy background samples to that completed episode.
            evidenceSnapshot.put("incidentId", "");
        }
        evidence.capture(targetCode, evidenceSnapshot, observed);
        return snapshot.deepCopy();
    }

    private void requireTarget(InternalActorTokens.Context actor) {
        if (!DemoTargetDtos.TARGETS.contains(actor.targetCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "DEMO_TARGET_NOT_ALLOWED");
        }
    }

    private void requireOperator(InternalActorTokens.Context actor) {
        if (actor.roles().stream()
                .noneMatch(role -> Set.of("ADMIN", "OPS", "DEMO").contains(role))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "DEMO_EXECUTION_NOT_ALLOWED");
        }
    }

    private boolean admin(InternalActorTokens.Context actor) {
        return actor.roles().contains("ADMIN") || actor.roles().contains("OPS");
    }

    /**
     * @author heyu
     */
    private static final class ProbeState {
        private final AtomicLong success = new AtomicLong(-1);
        private final AtomicLong observedEpoch = new AtomicLong();
        private int consecutiveSuccesses;
    }
}
