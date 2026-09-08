package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.security.InternalActorTokens.Context;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 2077 原始错误诊断的离线重现；只核验可从引用观测确定的数值及单位。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentMetricClaimsTest {
    private static final double ERROR_RATE = 0.6939090208172707;
    private static final double RPS = 1.4466146762795342;

    @Test
    void actualFailed2077PayloadCannotHideIncorrectPercentageBehindCorrectBusinessProbe()
            throws Exception {
        JsonNode fixture =
                AgentJson.MAPPER.readTree(
                        getClass().getResourceAsStream("/evidence/failed-2077-metric-claims.json"));
        var run = sample((ObjectNode) fixture.path("metrics"));
        String observationId = id(run);
        String now = Instant.now().toString();
        ObjectNode probe =
                AgentJson.object()
                        .put("targetCode", AgentTargets.ORDER)
                        .put("incidentId", "metric-incident")
                        .put("scope", "ISOLATED_DEMO")
                        .put("observedAt", now)
                        .put("redisPort", 6380);
        probe.putObject("business")
                .put("httpStatus", 503)
                .put("reasonCode", "REDIS_CONNECT_FAILED");
        register(run, "demo_target_inspect", "probe", probe);
        String probeId = currentId(run, "demo_target_inspect");
        ObjectNode changes =
                AgentJson.object()
                        .put("targetCode", AgentTargets.ORDER)
                        .put("incidentId", "metric-incident")
                        .put("current", true)
                        .put("observedAt", now);
        changes.putArray("changes")
                .addObject()
                .put("id", "change-2077")
                .put("occurredAt", now)
                .put("redisPort", 6380);
        register(run, "recent_changes", "change", changes);
        String changeId = currentId(run, "recent_changes");
        String raw =
                fixture.path("arguments")
                        .toString()
                        .replace("ev-ea8a6d05db901c0b760d3d84", observationId)
                        .replace("ev-ea8a6d05", observationId.substring(0, 11))
                        .replace("ev-64fcf00b0c4d93909ddaeddd", probeId)
                        .replace("ev-64fcf00b", probeId.substring(0, 11))
                        .replace("ev-2dc92790eea77d1a9db58d36", changeId)
                        .replace("ev-2dc92790", changeId.substring(0, 11));
        ObjectNode bad = (ObjectNode) AgentJson.read(raw);
        assertTrue(bad.path("evidence").asText().contains("businessProbe=0.0"));
        var rejected =
                assertThrows(
                        AgentEvidenceRegistry.ReferenceError.class,
                        () -> AgentEvidenceRegistry.validate(run, bad));
        assertTrue(rejected.getMessage().contains("errorRate"));
        assertTrue(rejected.getMessage().contains("0.6939090208172707"));
        ObjectNode corrected = (ObjectNode) AgentJson.read(raw.replace("69.39%", "0.69%"));
        assertDoesNotThrow(() -> AgentEvidenceRegistry.validate(run, corrected));
        assertTrue(bad.path("evidence").asText().contains("69.39%"));
    }

    @Test
    void sourceUnitsAndLegitimateRoundingAcceptEnglishChineseAndMarkdownClaims() {
        var run = sample(metrics());
        for (String text :
                List.of(
                        "errorRate=0.69%",
                        "error rate: 0.7 percent",
                        "error_rate 为 0.69 %",
                        "错误率约为０．６９％",
                        "错误率为百分之0.69",
                        "| `错误率（%）` | **0.69** |",
                        "metrics.errorRate.value=0.6939090208172707 %",
                        "RPS=1.45 requests/s",
                        "RPS=1.45/s",
                        "每秒请求数=1.45次/秒",
                        "请求速率为1.45 requests/s",
                        "P95Ms=120",
                        "P95延迟:120ms",
                        "P95 latency = 120 毫秒",
                        "RPS=1.45\nHTTP=503",
                        "RPS 1.45 and businessProbe=0.0",
                        "error rate is 0.69%",
                        "错误率达到0.69%")) {
            assertDoesNotThrow(
                    () -> AgentEvidenceRegistry.validate(run, diagnosis(run, text)), text);
        }
    }

    @Test
    void incorrectValuesAndImplicitUnitConversionsAreRejected() {
        var run = sample(metrics());
        for (String text :
                List.of(
                        "errorRate=69.39%",
                        "错误率：６９．３９％",
                        "errorRate=0.006939 ratio",
                        "errorRate=0.69",
                        "errorRate(ms)=0.69%",
                        "errorRate=NaN%",
                        "errorRate=Infinity%",
                        "errorRate=6.9e999%",
                        "RPS=5 requests/s",
                        "RPS=1.45 ms",
                        "RPS=1.45rpm",
                        "P95=0.12 s",
                        "P95=120%",
                        "P95Ms=121",
                        "P95=120",
                        "错误率达到69.39%",
                        "错误率高达69.39%",
                        "错误率测得69.39%")) {
            assertThrows(
                    AgentEvidenceRegistry.ReferenceError.class,
                    () ->
                            AgentEvidenceRegistry.validate(
                                    run, diagnosis(run, "businessProbe=0.0; " + text)),
                    text);
        }
    }

    @Test
    void everyDiagnosticFieldAndEveryClaimIsValidated() {
        var run = sample(metrics());
        for (String field :
                List.of(
                        "evidence",
                        "summary",
                        "knownFacts",
                        "candidateCauses",
                        "evidenceGaps",
                        "recommendation")) {
            ObjectNode args = diagnosis(run, "businessProbe=0.0; errorRate=0.69%");
            args.put(field, "RPS=1.45 requests/s; 当前错误率=69.39%");
            assertThrows(
                    AgentEvidenceRegistry.ReferenceError.class,
                    () -> AgentEvidenceRegistry.validate(run, args),
                    field);
        }
    }

    @Test
    void absentMeasurementOrUnitCannotBecomeAClaimEvenWhenAnotherFactIsUsable() {
        for (String missing : List.of("value", "unit")) {
            ObjectNode raw = metrics();
            ((ObjectNode) raw.path("errorRate")).remove(missing);
            var run = sample(raw);
            assertThrows(
                    AgentEvidenceRegistry.ReferenceError.class,
                    () ->
                            AgentEvidenceRegistry.validate(
                                    run, diagnosis(run, "businessProbe=0.0; errorRate=0.69%")));
        }
        ObjectNode raw = metrics();
        ((ObjectNode) raw.path("p95Ms")).putNull("value");
        var run = sample(raw);
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () ->
                        AgentEvidenceRegistry.validate(
                                run, diagnosis(run, "businessProbe=0.0; P95Ms=120")));
    }

    @Test
    void numericalClaimsCannotBypassValidationWithInsufficientConclusionAndNoReferences() {
        var run = sample(metrics());
        for (boolean omit : List.of(true, false)) {
            ObjectNode args =
                    AgentJson.object()
                            .put("conclusionLevel", "INSUFFICIENT")
                            .put("summary", "当前errorRate=69.39%");
            if (!omit) args.putArray("evidenceIds");
            assertThrows(
                    AgentEvidenceRegistry.ReferenceError.class,
                    () -> AgentEvidenceRegistry.validate(run, args));
        }
        assertDoesNotThrow(
                () ->
                        AgentEvidenceRegistry.validate(
                                run,
                                AgentJson.object()
                                        .put("conclusionLevel", "INSUFFICIENT")
                                        .put("summary", "错误率暂无可用观测")));
    }

    @Test
    void explicitSampleBindingIsRequiredWhenReferencedValuesDiffer() {
        var run = sample(metrics());
        String oldId = id(run);
        ObjectNode next = metrics();
        ((ObjectNode) next.path("errorRate")).put("value", 2.456);
        register(run, "observability_evidence", "next", observation(2077, next));
        String nextId = id(run);
        ObjectNode args = diagnosis(run, "businessProbe=0.0; errorRate=0.69%");
        args.putArray("evidenceIds").add(oldId).add(nextId);
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, args));
        args.put("evidence", oldId + " errorRate=0.69%\n" + nextId + " errorRate=2.46%");
        assertDoesNotThrow(() -> AgentEvidenceRegistry.validate(run, args));
        args.put("evidence", nextId + " errorRate=0.69%\n" + oldId + " businessProbe=0.0");
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, args));
        args.put("evidence", oldId + " 当前errorRate=0.69%\n" + nextId + " errorRate=2.46%");
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, args));
    }

    @Test
    void unreferencedOrForeignSampleCannotRescueAWrongClaim() {
        var run = sample(metrics());
        String actual = id(run);
        ObjectNode next = metrics();
        ((ObjectNode) next.path("errorRate")).put("value", 69.39);
        register(run, "observability_evidence", "unreferenced", observation(2077, next));
        ObjectNode args = diagnosis(run, "businessProbe=0.0; errorRate=69.39%");
        args.putArray("evidenceIds").add(actual);
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, args));
        args.put("evidence", id(run) + " errorRate=69.39%");
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, args));
        args.put("evidence", "ev-ffffffff errorRate=0.69%");
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, args));
        args.put("evidence", actual.substring(0, 11) + " errorRate=0.69%");
        assertDoesNotThrow(() -> AgentEvidenceRegistry.validate(run, args));
    }

    @Test
    void distinctSourcesWithIdenticalReadingsDoNotCreateFalseAmbiguity() {
        var run = sample(metrics());
        String first = id(run);
        register(run, "observability_evidence", "same", observation(2077, metrics()));
        ObjectNode args = diagnosis(run, "errorRate=0.69%");
        args.putArray("evidenceIds").add(first).add(id(run));
        assertDoesNotThrow(() -> AgentEvidenceRegistry.validate(run, args));
    }

    @Test
    void thresholdsPortsTimesAndSentinelQpsAreNotInterpretedAsObservedRps() {
        var run = sample(metrics());
        ObjectNode args =
                diagnosis(
                        run,
                        "businessProbe=0.0; errorRate=0.69%; RPS=1.45 requests/s; "
                                + "QPS=5, sentinelQps=5, redisPort=6380, 6379→6380, @22:07:44, "
                                + "d41a6e80-9974-4740-8c84-12439a797bcd");
        args.put(
                "recommendation",
                "建议设置阈值 errorRate=5%; threshold RPS=10 requests/s; "
                        + "目标值P95=200ms; target value errorRate=5%; 错误率不是69.39%");
        assertDoesNotThrow(() -> AgentEvidenceRegistry.validate(run, args));
        for (String prefix :
                List.of(
                        "target service ",
                        "demo_target_inspect ",
                        "if ",
                        "阈值errorRate=5%，但当前",
                        "建议将错误率设为5%；实际",
                        "并非阈值而是实测",
                        "当前指标突破阈值：",
                        "超过阈值的")) {
            args.put("recommendation", prefix + "errorRate=69.39%");
            assertThrows(
                    AgentEvidenceRegistry.ReferenceError.class,
                    () -> AgentEvidenceRegistry.validate(run, args),
                    prefix);
        }
    }

    @Test
    void thresholdMatchingTheRawValueStillCannotSupplyMeasuredEvidence() {
        var run = sample(metrics());
        ObjectNode args = diagnosis(run, "阈值 metrics.errorRate.value=0.6939090208172707%");
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, args));
        args.put("evidence", "metrics.errorRate.value=0.6939090208172707%");
        assertDoesNotThrow(() -> AgentEvidenceRegistry.validate(run, args));
        args.put("evidence", "businessProbe=0.0");
        assertDoesNotThrow(() -> AgentEvidenceRegistry.validate(run, args));
    }

    @Test
    void nonzeroMeasurementRequiresExplicitApproximationBeforeRoundingToZero() {
        ObjectNode raw = metrics();
        ((ObjectNode) raw.path("errorRate")).put("value", 0.004);
        var run = sample(raw);
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, diagnosis(run, "errorRate=0%")));
        assertDoesNotThrow(
                () -> AgentEvidenceRegistry.validate(run, diagnosis(run, "errorRate约0.00%")));
        assertDoesNotThrow(
                () -> AgentEvidenceRegistry.validate(run, diagnosis(run, "errorRate=0.004%")));
    }

    @Test
    void runtimeCorrectsBeforeAnyWriteCancelsQueuedRepairAndRetainsOriginalBudget() {
        AgentTestSupport fixture = runtime(false);
        String runId = createRuntime(fixture, "metric-correction");
        String deadline = fixture.store.get(runId).state().path("deadline").asText();
        for (int i = 0; i < 30 && fixture.clients.writes.isEmpty(); i++) fixture.step();
        var run = fixture.store.get(runId);
        assertEquals(1, fixture.clients.writes.size());
        assertTrue(
                fixture.clients
                        .writes
                        .get(0)
                        .path("input")
                        .path("evidence")
                        .asText()
                        .contains("0.69%"));
        assertFalse(fixture.clients.writes.get(0).toString().contains("69.39%"));
        assertEquals(1, run.state().path("referenceCorrections").asInt());
        assertEquals(30, run.state().path("tokens").asInt());
        assertEquals(40000, run.state().path("tokenBudget").asInt());
        assertEquals(deadline, run.state().path("deadline").asText());
        assertEquals(0, fixture.clients.actions);
        assertEquals(0, fixture.store.approvals(runId).size());
        assertEquals(
                1,
                fixture.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_event WHERE run_id=? AND"
                                + " event_type='TOOL_REQUEST_INTENT' AND payload_json LIKE"
                                + " '%preparedRequest%'",
                        Integer.class, runId));
    }

    @Test
    void repeatedNumericalFailureExhaustsBoundedCorrectionWithoutWritingOrApproving() {
        AgentTestSupport fixture = runtime(true);
        String runId = createRuntime(fixture, "metric-exhaustion");
        for (int i = 0; i < 40 && fixture.store.get(runId).status().equals("QUEUED"); i++)
            fixture.step();
        var run = fixture.store.get(runId);
        assertEquals("NEEDS_ATTENTION", run.status());
        assertEquals(2, run.state().path("referenceCorrections").asInt());
        assertEquals(40, run.state().path("tokens").asInt());
        assertTrue(run.state().path("message").asText().contains("数值核验失败"));
        assertEquals(0, fixture.clients.writes.size());
        assertEquals(0, fixture.clients.actions);
        assertEquals(0, fixture.store.approvals(runId).size());
        assertFalse(run.state().has("diagnosis"));
    }

    private static AgentTestSupport runtime(boolean repeatBad) {
        var clients =
                new AgentTestSupport.FakeClients() {
                    @Override
                    JsonNode call(
                            String audience,
                            String path,
                            String method,
                            JsonNode body,
                            Context actor) {
                        if (path.equals("/internal/platform/observability/evidence"))
                            return observation(7, metrics());
                        return super.call(audience, path, method, body, actor);
                    }
                };
        var fixture = new AgentTestSupport(clients);
        clients.model =
                request -> {
                    int turn = clients.modelRequests.size();
                    if (turn == 1)
                        return AgentTestSupport.response(
                                "observability_evidence", AgentJson.object());
                    var ids = Pattern.compile("ev-[a-f0-9]{24}").matcher(request.toString());
                    assertTrue(ids.find());
                    ObjectNode args =
                            AgentJson.object()
                                    .put("conclusionLevel", "SUPPORTED")
                                    .put("summary", "核验原始错误率")
                                    .put("recommendation", "恢复配置前需要人工审批")
                                    .put(
                                            "evidence",
                                            "businessProbe=0.0; errorRate="
                                                    + (turn == 2 || repeatBad
                                                            ? "69.39%"
                                                            : "0.69%"));
                    args.putArray("evidenceIds").add(ids.group());
                    ObjectNode response =
                            (ObjectNode) AgentTestSupport.response("ticket_add_analysis", args);
                    if (turn == 2) {
                        ObjectNode repair =
                                ((ArrayNode) response.path("assistantMessage").path("tool_calls"))
                                        .addObject()
                                        .put("id", "queued-repair")
                                        .put("type", "function");
                        repair.set(
                                "function",
                                AgentJson.object()
                                        .put("name", "demo_config_restore")
                                        .put(
                                                "arguments",
                                                "{\"expectedRevision\":\""
                                                        + "a".repeat(64)
                                                        + "\"}"));
                    }
                    return response;
                };
        return fixture;
    }

    private static String createRuntime(AgentTestSupport fixture, String trigger) {
        String id = fixture.create(trigger, -1);
        ObjectNode state = fixture.store.get(id).state();
        state.put("targetCode", AgentTargets.ORDER).put("tokenBudget", 40000);
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);
        return id;
    }

    private static ObjectNode diagnosis(AgentStore.Run run, String evidence) {
        ObjectNode args =
                AgentJson.object().put("conclusionLevel", "SUPPORTED").put("evidence", evidence);
        args.putArray("evidenceIds").add(id(run));
        return args;
    }

    private static String id(AgentStore.Run run) {
        return currentId(run, "observability_evidence");
    }

    private static String currentId(AgentStore.Run run, String tool) {
        return AgentEvidenceRegistry.currentEntries(run).stream()
                .filter(entry -> tool.equals(entry.path("tool").asText()))
                .findFirst()
                .orElseThrow()
                .path("id")
                .asText();
    }

    private static ObjectNode metrics() {
        ObjectNode metrics = AgentJson.object();
        metrics.putObject("errorRate").put("value", ERROR_RATE).put("unit", "%");
        metrics.putObject("rps").put("value", RPS).put("unit", "requests/s");
        metrics.putObject("p95Ms").put("value", 120).put("unit", "ms");
        return metrics;
    }

    private static ObjectNode observation(long ticket, ObjectNode metrics) {
        String now = Instant.now().toString();
        ObjectNode result =
                AgentJson.object()
                        .put("service", AgentTargets.ORDER)
                        .put("environment", "DEMO")
                        .put("ticketId", ticket)
                        .put("collectedAt", now);
        ObjectNode data =
                result.putArray("entries")
                        .addObject()
                        .put("id", "metric-source")
                        .put("quality", "READY")
                        .put("observedAt", now)
                        .putObject("data")
                        .put("businessProbe", 0.0);
        data.set("metrics", metrics.deepCopy());
        return result;
    }

    private static void register(
            AgentStore.Run run, String tool, String call, ObjectNode observation) {
        ((ObjectNode) run.state().path("observations")).set(call, observation);
        AgentEvidenceRegistry.register(
                run, AgentJson.object().put("id", call).put("name", tool), observation);
    }

    private static AgentStore.Run sample(ObjectNode metrics) {
        Instant now = Instant.now();
        ObjectNode state =
                AgentJson.object()
                        .put("targetCode", AgentTargets.ORDER)
                        .put("incidentId", "metric-incident")
                        .put("ticketId", 2077)
                        .put("deadline", now.plusSeconds(900).toString());
        state.putObject("observations");
        ObjectNode snapshot = AgentJson.object();
        snapshot.set("tools", AgentTools.schemas(AgentTargets.ORDER));
        var run =
                new AgentStore.Run(
                        "metric-run",
                        1,
                        "RUNNING",
                        "diagnose",
                        snapshot,
                        state,
                        1,
                        false,
                        false,
                        now.minusSeconds(1));
        register(run, "observability_evidence", "initial", observation(2077, metrics));
        return run;
    }
}
