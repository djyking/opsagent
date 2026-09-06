package com.opsagent.platform;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 复用V2事实聚合后关联实际调用、实例、历史及差异；不新增CMDB事实源。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class ObservabilityV3Service {
    private final TopologyAggregationService topology;
    private final ItsmPlatformService cmdb;
    private final TraceEvidenceAdapter traces;
    private final ObservabilityV3Repository repository;
    private final PlatformAuditRepository audit;

    ObservabilityV3Service(
            TopologyAggregationService topology,
            ItsmPlatformService cmdb,
            TraceEvidenceAdapter traces,
            ObservabilityV3Repository repository,
            PlatformAuditRepository audit) {
        this.topology = topology;
        this.cmdb = cmdb;
        this.traces = traces;
        this.repository = repository;
        this.audit = audit;
    }

    Map<String, Object> topology(String environment, String range, String mode) {
        TraceEvidenceAdapter.environment(environment);
        long seconds = TraceEvidenceAdapter.seconds(range);
        if (!Set.of("CONFIGURED", "HYBRID", "OBSERVED").contains(mode))
            throw TraceEvidenceAdapter.invalid("关系模式无效");
        Map<String, Object> base =
                new LinkedHashMap<>(topology.topology(environment, range, "CONFIGURED"));
        List<Map<String, Object>> nodes =
                new ArrayList<>(TraceEvidenceAdapter.rows(base.get("nodes")));
        List<Map<String, Object>> configured = TraceEvidenceAdapter.rows(base.get("edges"));
        var graph = traces.graph(range);
        List<Map<String, Object>> observed = observed(graph, environment, nodes);
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> matches = new HashSet<>();
        for (var edge : observed) {
            var match = configured.stream().filter(c -> match(c, edge)).findFirst();
            if (match.isPresent()) {
                edge.put("relationSource", "MATCHED");
                edge.put("configuredRelationId", match.get().get("id"));
                matches.add(String.valueOf(match.get().get("id")));
            }
        }
        if (!"OBSERVED".equals(mode))
            configured.stream()
                    .filter(
                            edge ->
                                    !"HYBRID".equals(mode)
                                            || !matches.contains(String.valueOf(edge.get("id"))))
                    .forEach(edges::add);
        if (!"CONFIGURED".equals(mode)) edges.addAll(observed);
        if ("CONFIGURED".equals(mode))
            nodes.removeIf(node -> Boolean.TRUE.equals(node.get("virtual")));
        base.put("nodes", nodes);
        base.put("edges", edges);
        base.put("mode", mode);
        base.put("windowStart", graph.fetchedAt().minusSeconds(seconds).toString());
        base.put("windowEnd", graph.fetchedAt().toString());
        base.put("graphVersion", version(nodes, edges));
        base.put(
                "trace",
                Map.of(
                        "status",
                        graph.status(),
                        "message",
                        graph.message(),
                        "samplingPolicy",
                        "PROD roots 10%; DEMO roots 100%; parent decision inherited. Edge rates"
                                + " represent sampled pairs, not total HTTP traffic."));
        base.put(
                "relationMessage",
                mode.equals("CONFIGURED") ? "登记关系与实际调用分开保存；切换混合或实际调用核对" : graph.message());
        var sources = new ArrayList<>(TraceEvidenceAdapter.rows(base.get("dataSources")));
        sources.removeIf(source -> "Trace".equals(source.get("name")));
        sources.add(
                Map.of(
                        "name",
                        "Trace",
                        "healthy",
                        Set.of("READY", "NO_DATA").contains(graph.status()),
                        "message",
                        graph.message()));
        base.put("dataSources", sources);
        base.put(
                "dataQuality",
                sources.stream().allMatch(source -> Boolean.TRUE.equals(source.get("healthy")))
                        ? "AVAILABLE"
                        : "PARTIAL");
        return base;
    }

    private List<Map<String, Object>> observed(
            TraceEvidenceAdapter.Graph graph, String environment, List<Map<String, Object>> nodes) {
        Map<String, Map<String, Object>> known = new HashMap<>();
        cmdb.cis(null, null)
                .forEach(ci -> known.put(ci.get("environment") + ":" + ci.get("ciCode"), ci));
        List<Map<String, Object>> result = new ArrayList<>();
        for (var raw : graph.edges()) {
            String source = String.valueOf(raw.get("sourceService"));
            String target = String.valueOf(raw.get("targetService"));
            String[] from = identity(source);
            String[] to = identity(target);
            if (!"ALL".equals(environment)
                    && !environment.equals(from[0])
                    && !environment.equals(to[0])) continue;
            if (from[0].isBlank() && to[0].isBlank()) continue;
            Map<String, Object> edge = new LinkedHashMap<>(raw);
            edge.put("sourceCiCode", addNode(source, from, known, nodes, to[0]));
            edge.put("targetCiCode", addNode(target, to, known, nodes, from[0]));
            edge.put("sourceEnvironment", from[0]);
            edge.put("targetEnvironment", to[0]);
            edge.put("scope", environment);
            edge.put(
                    "identityResolved",
                    known.containsKey(from[0] + ":" + from[1])
                            && known.containsKey(to[0] + ":" + to[1]));
            result.add(edge);
        }
        return result;
    }

    private String addNode(
            String name,
            String[] identity,
            Map<String, Map<String, Object>> known,
            List<Map<String, Object>> nodes,
            String environment) {
        Map<String, Object> ci = known.get(identity[0] + ":" + identity[1]);
        String peerEnvironment = peerEnvironment(identity[0], environment);
        String code =
                ci == null
                        ? unresolvedPeerCode(name, peerEnvironment)
                        : String.valueOf(ci.get("ciCode"));
        if (nodes.stream().anyMatch(node -> code.equals(node.get("ciCode")))) return code;
        Map<String, Object> node = new LinkedHashMap<>();
        if (ci != null) {
            node.putAll(ci);
            node.put("statusReason", "当前查询环境之外的真实依赖；点击按其所属环境核对健康");
            node.put("healthScope", "OUTSIDE_SELECTED_ENVIRONMENT");
        } else {
            node.put("ciCode", code);
            node.put("ciName", name);
            node.put("ciType", "EXTERNAL_API");
            node.put("environment", peerEnvironment);
            node.put("virtual", true);
            node.put("statusReason", "客户端观测到的peer，尚未可靠映射CI；不能据此判断服务器健康");
            node.put("healthScope", "CLIENT_PEER_ONLY");
        }
        node.put("health", "UNKNOWN");
        node.put("metrics", Map.of());
        node.put("requiresObservation", false);
        nodes.add(node);
        return code;
    }

    static String peerEnvironment(String declared, String counterpart) {
        Set<String> environments = Set.of("PROD", "DEMO", "DEV", "TEST", "STAGING");
        return environments.contains(declared)
                ? declared
                : environments.contains(counterpart) ? counterpart : "UNKNOWN";
    }

    static String unresolvedPeerCode(String name, String environment) {
        return "trace-peer-"
                + TraceEvidenceAdapter.id(peerEnvironment(environment, "") + ":" + name);
    }

    static String[] identity(String name) {
        String[] parts = name.split(":", 3);
        return parts.length == 3
                        && parts[0].equals("opsagent")
                        && Set.of("PROD", "DEMO", "DEV", "TEST", "STAGING").contains(parts[1])
                        && parts[2].matches("[a-zA-Z0-9_-]{1,64}")
                ? new String[] {parts[1], parts[2]}
                : new String[] {"", ""};
    }

    static boolean match(Map<String, Object> configured, Map<String, Object> observed) {
        if (!java.util.Objects.equals(configured.get("sourceCiCode"), observed.get("sourceCiCode"))
                || !java.util.Objects.equals(
                        configured.get("targetCiCode"), observed.get("targetCiCode"))) return false;
        String declared = String.valueOf(configured.get("relationType"));
        String actual = String.valueOf(observed.get("relationType"));
        return declared.equals(actual)
                || Set.of("READS_FROM", "WRITES_TO", "USES_DATABASE", "USES_CACHE")
                                .contains(declared)
                        && actual.equals("ACCESSES_DATA");
    }

    Map<String, Object> differences(String environment, String range) {
        Map<String, Object> data = topology(environment, range, "HYBRID");
        Map<?, ?> trace = (Map<?, ?>) data.get("trace");
        boolean available =
                Set.of("READY", "NO_DATA").contains(String.valueOf(trace.get("status")));
        List<Map<String, Object>> result = new ArrayList<>();
        for (var edge : TraceEvidenceAdapter.rows(data.get("edges"))) {
            Map<String, Object> row = new LinkedHashMap<>(edge);
            String source = String.valueOf(edge.get("relationSource"));
            String category =
                    "MATCHED".equals(source)
                            ? "MATCHED"
                            : "OBSERVED".equals(source)
                                    ? Boolean.TRUE.equals(edge.get("identityResolved"))
                                            ? "OBSERVED_ONLY"
                                            : "UNRESOLVED_IDENTITY"
                                    : available ? "CONFIGURED_NOT_OBSERVED" : "INDETERMINATE";
            String id =
                    TraceEvidenceAdapter.id(
                            environment
                                    + ":"
                                    + edge.get("sourceCiCode")
                                    + ":"
                                    + edge.get("targetCiCode")
                                    + ":"
                                    + edge.get("relationType"));
            row.put("id", id);
            row.put("category", category);
            row.put(
                    "reason",
                    switch (category) {
                        case "MATCHED" -> "登记类型与本窗口真实采样调用匹配，保留双源证据";
                        case "OBSERVED_ONLY" -> "发现未精确登记的实际调用；可人工核对，不会自动修改CMDB";
                        case "UNRESOLVED_IDENTITY" -> "服务或peer身份未可靠映射，不能按显示名强行合并";
                        case "CONFIGURED_NOT_OBSERVED" -> "本窗口未观测到兼容调用；可能无流量、采样或类型过于宽泛，不表示依赖已消失";
                        default -> "Trace接入或新鲜度不足，当前无法判断差异";
                    });
            row.putIfAbsent("evidenceRefs", List.of("cmdb-relation:" + edge.get("id")));
            result.add(row);
        }
        var handling =
                repository.handling(
                        result.stream().map(row -> String.valueOf(row.get("id"))).toList());
        result.forEach(
                row ->
                        row.put(
                                "handling",
                                handling.getOrDefault(
                                        String.valueOf(row.get("id")),
                                        Map.of("decision", "OPEN", "note", ""))));
        return Map.of(
                "items",
                result,
                "traceStatus",
                trace.get("status"),
                "environment",
                environment,
                "windowStart",
                data.get("windowStart"),
                "windowEnd",
                data.get("windowEnd"));
    }

    void decision(String id, String decision, String note, Instant ignoreUntil) {
        if (!id.matches("[a-f0-9-]{36}")
                || !Set.of("ACKNOWLEDGED", "IGNORE").contains(decision)
                || note == null
                || note.isBlank()
                || note.length() > 500
                || decision.equals("IGNORE")
                        && (ignoreUntil == null
                                || !ignoreUntil.isAfter(Instant.now())
                                || ignoreUntil.isAfter(Instant.now().plusSeconds(7 * 86400))))
            throw TraceEvidenceAdapter.invalid("差异处理或忽略期限无效");
        Map<String, Object> found = null;
        String env = "";
        for (String scope : List.of("ALL", "PROD", "DEMO", "STAGING", "TEST", "DEV")) {
            for (var row : TraceEvidenceAdapter.rows(differences(scope, "6h").get("items")))
                if (id.equals(row.get("id"))) {
                    found = row;
                    env = scope;
                    break;
                }
            if (found != null) break;
        }
        if (found == null) throw new BusinessException(ErrorCode.NOT_FOUND, "未找到当前可核对的关系差异");
        var actor = SecurityUsers.current();
        repository.decision(
                id,
                env,
                decision,
                ObservabilitySanitizer.summary(note),
                "IGNORE".equals(decision) ? ignoreUntil : null,
                actor.userId());
        audit.addPlatform(
                "TOPOLOGY_DIFFERENCE",
                id,
                "DIFFERENCE_" + decision,
                actor.userId(),
                repository.write(Map.of("note", ObservabilitySanitizer.summary(note))));
    }

    Map<String, Object> instances(String code, String range) {
        var ci = cmdb.ci(code);
        String env = String.valueOf(ci.get("environment"));
        String runtime = TraceEvidenceAdapter.runtimeCode(code);
        long deadline = TraceEvidenceAdapter.deadline();
        Map<String, Object> samples = traces.search(runtime, env, range, null, deadline);
        List<Map<String, Object>> observed = new ArrayList<>();
        for (var item :
                TraceEvidenceAdapter.rows(samples.get("items")).stream().limit(5).toList()) {
            if (System.nanoTime() >= deadline) break;
            try {
                observed.addAll(
                        TraceEvidenceAdapter.rows(
                                        traces.trace(
                                                        String.valueOf(item.get("traceId")),
                                                        env,
                                                        runtime,
                                                        deadline)
                                                .get("spans"))
                                .stream()
                                .filter(span -> runtime.equals(span.get("ciCode")))
                                .toList());
            } catch (RuntimeException unavailable) {
                /* Keep previously observed instances labelled with their actual lastSeen time. */
            }
        }
        repository.recordSpans(observed);
        var instances =
                repository.instances(
                        runtime,
                        env,
                        Instant.now().minusSeconds(TraceEvidenceAdapter.seconds(range)));
        return Map.of(
                "ciCode",
                code,
                "environment",
                env,
                "runtimeServiceCiCode",
                runtime,
                "items",
                instances,
                "status",
                samples.get("status"),
                "podSupported",
                false,
                "message",
                runtime.equals(code)
                        ? "真实Trace资源实例；未在窗口收到Trace不等于进程已停止"
                        : "通知与订单是同一Docker应用中的两个逻辑服务，复用同一真实进程实例，不伪造副本");
    }

    Map<String, Object> search(String code, String env, String range, String target) {
        String authoritative = serviceEnvironment(code, env);
        if (target != null && !target.isBlank()) cmdb.ci(target);
        return traces.search(code, authoritative, range, target);
    }

    Map<String, Object> trace(String id, String env, String code) {
        String authoritative = serviceEnvironment(code, env);
        var result = traces.trace(id, authoritative, code);
        repository.recordSpans(TraceEvidenceAdapter.rows(result.get("spans")));
        return result;
    }

    private String serviceEnvironment(String code, String requested) {
        TraceEvidenceAdapter.environment(requested);
        String authoritative = String.valueOf(cmdb.ci(code).get("environment"));
        if (!requested.equals("ALL") && !requested.equals(authoritative))
            throw TraceEvidenceAdapter.invalid("服务与环境身份不一致");
        return authoritative;
    }

    Map<String, Object> history(String env, Instant from, Instant to) {
        TraceEvidenceAdapter.environment(env);
        if (from == null
                || to == null
                || from.isAfter(to)
                || Duration.between(from, to).compareTo(Duration.ofHours(72)) > 0
                || to.isAfter(Instant.now().plusSeconds(60)))
            throw TraceEvidenceAdapter.invalid("历史查询最多72小时且不能查询未来");
        return repository.history(env, from, to);
    }

    Map<String, Object> history(String id) {
        if (!id.matches("[a-f0-9-]{36}")) throw TraceEvidenceAdapter.invalid("历史ID无效");
        return repository.snapshot(id);
    }

    private String version(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        List<String> structure = new ArrayList<>();
        nodes.forEach(
                node ->
                        structure.add(
                                "N:"
                                        + node.get("environment")
                                        + ":"
                                        + node.get("ciCode")
                                        + ":"
                                        + node.get("ciName")
                                        + ":"
                                        + node.get("ciType")));
        edges.forEach(
                edge ->
                        structure.add(
                                "E:"
                                        + edge.get("sourceCiCode")
                                        + ":"
                                        + edge.get("targetCiCode")
                                        + ":"
                                        + edge.get("relationType")
                                        + ":"
                                        + edge.get("relationSource")));
        structure.sort(String::compareTo);
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            String.join("\n", structure)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
