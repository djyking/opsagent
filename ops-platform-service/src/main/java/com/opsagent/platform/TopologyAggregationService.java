package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CMDB 关系与实时观测独立聚合；缺少 Trace 时明确保留 CONFIGURED 来源。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class TopologyAggregationService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TrafficGovernanceService traffic;

    private static final Map<String, String> JOBS =
            Map.ofEntries(
                    Map.entry("ops-gateway", "opsagent-gateway"),
                    Map.entry("ops-auth-service", "opsagent-auth"),
                    Map.entry("ops-ticket-service", "opsagent-ticket"),
                    Map.entry("ops-knowledge-service", "opsagent-knowledge"),
                    Map.entry("ops-rag-service", "opsagent-rag"),
                    Map.entry("ops-platform-service", "opsagent-platform"),
                    Map.entry("ops-agent-service", "opsagent-agent"),
                    Map.entry("rabbitmq", "opsagent-rabbitmq"),
                    Map.entry("nacos", "opsagent-nacos"),
                    Map.entry("mysql", "opsagent-mysql"),
                    Map.entry("redis", "opsagent-redis"),
                    Map.entry("elasticsearch", "opsagent-elasticsearch"),
                    Map.entry("grafana", "opsagent-grafana"),
                    Map.entry("sentinel", "opsagent-sentinel"),
                    Map.entry("qdrant", "opsagent-qdrant"),
                    Map.entry("prometheus", "opsagent-prometheus"),
                    Map.entry("alertmanager", "opsagent-alertmanager"),
                    Map.entry("ops-demo-rabbitmq", "opsagent-demo-rabbitmq"),
                    Map.entry("ops-demo-order-service", "opsagent-demo-order"),
                    Map.entry("ops-demo-notification-service", "opsagent-demo-order"));
    private final ItsmPlatformService cmdb;
    private final PrometheusAdapter prometheus;
    private final AlertmanagerAdapter alertmanager;
    private final NodeHealthService health;
    private final ObservabilityRepository repository;
    private final PlatformAuditRepository audit;
    private final ObjectMapper json;
    private final NodeObservationService observations = new NodeObservationService();
    private final Map<String, PrometheusAdapter.Snapshot> metricsCache = new LinkedHashMap<>();
    private AlertmanagerAdapter.Snapshot alertCache;

    TopologyAggregationService(
            ItsmPlatformService cmdb,
            PrometheusAdapter prometheus,
            AlertmanagerAdapter alertmanager,
            NodeHealthService health,
            ObservabilityRepository repository,
            PlatformAuditRepository audit,
            ObjectMapper json) {
        this.cmdb = cmdb;
        this.prometheus = prometheus;
        this.alertmanager = alertmanager;
        this.health = health;
        this.repository = repository;
        this.audit = audit;
        this.json = json;
    }

    Map<String, Object> topology(String environment, String range, String mode) {
        if (!Set.of("5m", "15m", "30m", "1h", "6h").contains(range)
                || !Set.of("CONFIGURED", "HYBRID", "OBSERVED").contains(mode)
                || !environment.matches("[A-Z0-9_-]{1,32}")) {
            throw new BusinessException(ErrorCode.VALIDATION, "不支持的观测范围或拓扑模式");
        }
        PrometheusAdapter.Snapshot metrics = metrics(range);
        AlertmanagerAdapter.Snapshot alerts = alerts();
        Set<String> drilling = repository.drilling();
        List<Map<String, Object>> nodes =
                cmdb.cis(null, null).stream()
                        .filter(
                                ci ->
                                        "ALL".equals(environment)
                                                || environment.equals(ci.get("environment")))
                        .map(ci -> node(ci, metrics, alerts, drilling, range))
                        .toList();
        Set<String> codes = new java.util.HashSet<>();
        nodes.forEach(node -> codes.add(String.valueOf(node.get("ciCode"))));
        List<Map<String, Object>> edges =
                "OBSERVED".equals(mode)
                        ? List.of()
                        : cmdb.relations().stream()
                                .filter(
                                        edge ->
                                                codes.contains(edge.get("sourceCiCode"))
                                                        && codes.contains(edge.get("targetCiCode")))
                                .map(
                                        edge -> {
                                            Map<String, Object> value = new LinkedHashMap<>(edge);
                                            value.put("relationSource", "CONFIGURED");
                                            value.put("rps", null);
                                            value.put("errorRate", null);
                                            value.put("p95Ms", null);
                                            return value;
                                        })
                                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("coverage", coverage(nodes));
        result.put("edges", edges);
        result.put("checkedAt", Instant.now());
        result.put("environment", environment);
        result.put("timeRange", range);
        result.put("mode", mode);
        result.put("relationMessage", "尚未接入 Trace；关系来源为 CMDB 登记，未生成边调用指标");
        result.put("layout", repository.layout(environment));
        Map<String, Map<String, Object>> activeAlerts = new LinkedHashMap<>();
        for (var node : nodes) {
            for (var alert : relatedAlerts(node, alerts)) {
                Map<String, Object> row = new LinkedHashMap<>(alert.view());
                if (alert.ciCode().isBlank()) row.put("ciCode", node.get("ciCode"));
                activeAlerts.putIfAbsent(alert.id(), row);
            }
        }
        result.put("activeAlerts", List.copyOf(activeAlerts.values()));
        result.put("activeAlertCount", alerts.healthy() ? activeAlerts.size() : null);
        result.put(
                "dataSources",
                List.of(
                        source("Prometheus", metrics.healthy(), metrics.message()),
                        source("Alertmanager", alerts.healthy(), alerts.message()),
                        source("Trace", false, "暂无运行时关系数据")));
        return result;
    }

    Map<String, Object> recoveryNode(String code) {
        return node(cmdb.ci(code), metrics("5m"), alerts(), Set.of(), "5m");
    }

    Map<String, Object> detail(String code, String range) {
        Map<String, Object> ci = cmdb.ci(code);
        if (!Set.of("5m", "15m", "30m", "1h", "6h").contains(range)) {
            throw new BusinessException(ErrorCode.VALIDATION, "不支持的观测时间范围");
        }
        var metrics = metrics(range);
        var alerts = alerts();
        var node = node(ci, metrics, alerts, repository.drilling(), range);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node", node);
        result.put(
                "alerts",
                relatedAlerts(ci, alerts).stream()
                        .limit(5)
                        .map(AlertmanagerAdapter.Alert::view)
                        .toList());
        result.put("alertsAvailable", alerts.healthy());
        result.put(
                "relations",
                cmdb.relations().stream()
                        .filter(
                                edge ->
                                        code.equals(edge.get("sourceCiCode"))
                                                || code.equals(edge.get("targetCiCode")))
                        .toList());
        result.put("recentChanges", repository.recentChanges(code, SecurityUsers.current()));
        result.put("recentRuns", repository.recentRuns(code, SecurityUsers.current()));
        result.put("checkedAt", Instant.now());
        return result;
    }

    @Transactional
    void saveLayout(ObservabilityDtos.Layout request) {
        validateLayout(request);
        long actor = SecurityUsers.current().userId();
        repository.layout(request, actor);
        audit.addPlatform(
                "TOPOLOGY",
                request.environment(),
                "TOPOLOGY_LAYOUT_SAVE",
                actor,
                "{\"nodeCount\":" + request.positions().size() + "}");
    }

    Map<String, Object> currentLayout(String environment) {
        TraceEvidenceAdapter.environment(environment);
        return repository.currentLayout(environment, SecurityUsers.current().userId());
    }

    @Transactional
    void savePersonalLayout(ObservabilityDtos.Layout request) {
        validateLayout(request);
        repository.personalLayout(request, SecurityUsers.current().userId());
    }

    void resetPersonalLayout(String environment) {
        TraceEvidenceAdapter.environment(environment);
        repository.resetPersonalLayout(environment, SecurityUsers.current().userId());
    }

    private void validateLayout(ObservabilityDtos.Layout request) {
        TraceEvidenceAdapter.environment(request.environment());
        Set<String> codes = new java.util.HashSet<>();
        cmdb.cis(null, null).stream()
                .filter(
                        ci ->
                                "ALL".equals(request.environment())
                                        || request.environment().equals(ci.get("environment")))
                .forEach(ci -> codes.add(String.valueOf(ci.get("ciCode"))));
        Set<String> seen = new java.util.HashSet<>();
        for (var position : request.positions()) {
            if (!codes.contains(position.ciCode())
                    || !seen.add(position.ciCode())
                    || !Double.isFinite(position.x())
                    || !Double.isFinite(position.y())
                    || Math.abs(position.x()) > 100000
                    || Math.abs(position.y()) > 100000) {
                throw new BusinessException(ErrorCode.VALIDATION, "布局包含未知或重复节点或无效坐标");
            }
        }
    }

    synchronized PrometheusAdapter.Snapshot metrics(String range) {
        var cached = metricsCache.get(range);
        if (cached != null && cached.checkedAt().isAfter(Instant.now().minusSeconds(10)))
            return cached;
        var data = prometheus.collect(range);
        metricsCache.put(range, data);
        return data;
    }

    private synchronized AlertmanagerAdapter.Snapshot alerts() {
        if (alertCache != null && alertCache.checkedAt().isAfter(Instant.now().minusSeconds(10)))
            return alertCache;
        alertCache = alertmanager.collect();
        return alertCache;
    }

    private Map<String, Object> node(
            Map<String, Object> ci,
            PrometheusAdapter.Snapshot snapshot,
            AlertmanagerAdapter.Snapshot alerts,
            Set<String> drilling,
            String range) {
        String code = String.valueOf(ci.get("ciCode"));
        String job = binding(ci, "prometheusJob", JOBS.getOrDefault(code, ""));
        String environment = String.valueOf(ci.getOrDefault("environment", "UNKNOWN"));
        String namespace = String.valueOf(ci.getOrDefault("namespace", ""));
        String cluster = String.valueOf(ci.getOrDefault("cluster", ""));
        boolean legacy =
                job.equals(JOBS.get(code))
                        && environment.equals(code.startsWith("ops-demo-") ? "DEMO" : "PROD");
        snapshot =
                observations.scoped(snapshot, job, code, environment, namespace, cluster, legacy);
        Instant now = Instant.now();
        long age = prometheus.maximumSampleAge() > 0 ? prometheus.maximumSampleAge() : 90;
        var metricEvidence = observations.metricEvidence(snapshot, range, now, age);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metricEvidence.forEach((name, evidence) -> metrics.put(name, evidence.value()));
        var business = observations.probe(snapshot, now, age);
        Double probe = business.value();
        Instant probeAt = business.observedAt();
        var state =
                health.compute(
                        job,
                        String.valueOf(ci.get("status")),
                        snapshot.series().getOrDefault("up", List.of()),
                        relatedAlerts(ci, alerts),
                        metrics,
                        now,
                        probe,
                        probeAt,
                        snapshot.targets().size(),
                        Set.of("ops-demo-order-service", "ops-demo-notification-service")
                                .contains(code));
        metrics.put("healthyInstances", state.healthyInstances());
        metrics.put("totalInstances", state.totalInstances());
        Map<String, Object> node = new LinkedHashMap<>(ci);
        node.put("metrics", metrics);
        node.put("health", state.health());
        node.put("healthReasonCode", state.reasonCode());
        node.put("healthScope", state.scope());
        node.put("identity", observations.identity(ci, "", namespace, cluster));
        if ("ops-demo-notification-service".equals(code)) {
            node.put("sharedRuntimeWith", "ops-demo-order-service");
            node.put("runtimeScopeMessage", "JVM/HTTP指标来自共享Demo进程；通知业务探针按service独立关联");
        }
        node.put(
                "observation",
                observations.observe(ci, job, snapshot, metricEvidence, now, age, probe, probeAt));
        node.put("metricEvidence", metricEvidence);
        String status = String.valueOf(ci.getOrDefault("status", "ACTIVE"));
        node.put(
                "lifecycle",
                "RETIRED".equals(status)
                        ? "RETIRED"
                        : Set.of("INACTIVE", "DISABLED").contains(status) ? "INACTIVE" : "ACTIVE");
        node.put("requiresObservation", !Boolean.FALSE.equals(ci.get("requiresObservation")));
        node.put(
                "overlays",
                Map.of(
                        "maintenance",
                        "MAINTENANCE".equals(status),
                        "drilling",
                        drilling.contains(code),
                        "alertSilenced",
                        false));
        Instant healthAt =
                "ACTIVE_ALERT".equals(state.scope())
                        ? alerts.checkedAt()
                        : Set.of("JVM_RUNTIME", "REQUEST_WINDOW", "NATIVE_METRICS")
                                        .contains(state.scope())
                                ? metricEvidence.values().stream()
                                        .filter(
                                                e ->
                                                        e.value() != null
                                                                && state.scope().equals(e.scope()))
                                        .map(ObservabilityDtos.MetricEvidence::sampledAt)
                                        .filter(java.util.Objects::nonNull)
                                        .min(Instant::compareTo)
                                        .orElse(null)
                                : state.observedAt();
        boolean hasHealthEvidence =
                healthAt != null && !"BUSINESS_EVIDENCE_MISSING".equals(state.reasonCode());
        String evidenceId = environment + ":" + code + ":" + state.scope();
        node.put("evidenceRefs", hasHealthEvidence ? List.of(evidenceId) : List.of());
        node.put(
                "evidence",
                hasHealthEvidence
                        ? List.of(
                                Map.of(
                                        "id",
                                        evidenceId,
                                        "source",
                                        state.scope(),
                                        "observedAt",
                                        healthAt,
                                        "summary",
                                        state.reason()))
                        : List.of());
        node.put("statusReason", state.reason());
        node.put("observedAt", healthAt);
        node.put("prometheusJob", job);
        node.put("activeAlertCount", alerts.healthy() ? relatedAlerts(ci, alerts).size() : null);
        node.put("sentinelBlockQps", null);
        if (traffic != null && "ops-rag-service".equals(code)) {
            try {
                var summary = traffic.summary(code);
                node.put("sentinelBlockQps", summary.blockQps());
                node.put("sentinelRuleCount", summary.ruleCount());
                node.put("sentinelStatus", summary.status());
                // Blocking is a policy observation, not proof of a failed business operation.
            } catch (RuntimeException ignored) {
                node.put("sentinelStatus", "UNKNOWN");
            }
        }
        node.put("drilling", drilling.contains(code));
        node.put("businessProbe", probe);
        node.put("businessObservedAt", probeAt);
        return node;
    }

    private Map<String, Object> coverage(List<Map<String, Object>> nodes) {
        int eligible = 0;
        int ready = 0;
        int partial = 0;
        int failed = 0;
        int unknown = 0;
        for (var node : nodes) {
            if (!"ACTIVE".equals(node.get("lifecycle"))
                    || Boolean.FALSE.equals(node.get("requiresObservation"))) {
                continue;
            }
            eligible++;
            var observation = (ObservabilityDtos.Observation) node.get("observation");
            switch (observation.status()) {
                case "READY" -> ready++;
                case "PARTIAL" -> partial++;
                case "FAILED" -> failed++;
                default -> unknown++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eligible", eligible);
        result.put("ready", ready);
        result.put("partial", partial);
        result.put("failed", failed);
        result.put("unknown", unknown);
        result.put("excluded", nodes.size() - eligible);
        result.put("ratio", eligible == 0 ? null : (ready + partial) / (double) eligible);
        result.put("completeRatio", eligible == 0 ? null : ready / (double) eligible);
        result.put("definition", "生命周期ACTIVE且requiresObservation；未接入和不支持仍计入分母");
        return result;
    }

    private List<AlertmanagerAdapter.Alert> relatedAlerts(
            Map<String, Object> ci, AlertmanagerAdapter.Snapshot alerts) {
        String code = String.valueOf(ci.get("ciCode"));
        String alias = binding(ci, "alertLabel", code);
        String job = binding(ci, "prometheusJob", JOBS.getOrDefault(code, ""));
        return alerts.alerts().stream()
                .filter(
                        alert ->
                                alert.environment().isBlank()
                                        ? String.valueOf(ci.get("environment"))
                                                        .equals(
                                                                code.startsWith("ops-demo-")
                                                                        ? "DEMO"
                                                                        : "PROD")
                                                && (code.equals(alert.ciCode())
                                                        || job.equals(JOBS.get(code)))
                                        : alert.environment().equals(ci.get("environment")))
                .filter(
                        alert ->
                                alert.namespace().isBlank()
                                        || alert.namespace().equals(ci.get("namespace")))
                .filter(
                        alert ->
                                alert.cluster().isBlank()
                                        || alert.cluster().equals(ci.get("cluster")))
                .filter(
                        alert ->
                                code.equals(alert.ciCode())
                                        || alias.equals(alert.ciCode())
                                        || alert.ciCode().isBlank()
                                                && !job.isBlank()
                                                && job.equals(alert.job()))
                .toList();
    }

    private String binding(Map<String, Object> ci, String key, String fallback) {
        JsonNode bindings = json.valueToTree(ci.get("bindings"));
        String value = bindings.path(key).asText("");
        return value.isBlank() ? fallback : value;
    }

    private Map<String, Object> source(String name, boolean healthy, String message) {
        return Map.of("name", name, "healthy", healthy, "message", message);
    }
}
