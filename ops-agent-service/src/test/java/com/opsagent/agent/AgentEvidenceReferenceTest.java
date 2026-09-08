package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * 2074 失败输入的脱敏离线样本；不请求真实模型、不操作本地业务数据。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentEvidenceReferenceTest {
    private List<AgentStore.Run> failures() throws Exception {
        JsonNode rows =
                AgentJson.MAPPER.readTree(
                        getClass().getResourceAsStream("/evidence/failed-2074.json"));
        java.util.ArrayList<AgentStore.Run> runs = new java.util.ArrayList<>();
        for (JsonNode row : rows) {
            ObjectNode snapshot = AgentJson.object();
            snapshot.set("tools", row.path("tools").deepCopy());
            runs.add(
                    new AgentStore.Run(
                            row.path("id").asText(),
                            1,
                            "NEEDS_ATTENTION",
                            "diagnose",
                            snapshot,
                            (ObjectNode) row.path("state").deepCopy(),
                            1,
                            false,
                            false,
                            Instant.parse(row.path("createdAt").asText())));
        }
        return runs;
    }

    private static void modern(AgentStore.Run run) {
        run.snapshot().set("tools", AgentTools.schemas(AgentTargets.ORDER));
    }

    private static JsonNode entry(AgentStore.Run run, String tool) {
        return AgentEvidenceRegistry.currentEntries(run).stream()
                .filter(e -> tool.equals(e.path("tool").asText()))
                .findFirst()
                .orElseThrow();
    }

    private static ObjectNode supported(String... ids) {
        ObjectNode args =
                AgentJson.object()
                        .put("summary", "端口变更后连接失败，需要核验 Redis 实际监听")
                        .put(
                                "evidence",
                                "httpStatus=503，reasonCode=REDIS_CONNECT_FAILED；redisPort"
                                        + " 6379→6380")
                        .put("recommendation", "人工审批恢复固定配置后实测")
                        .put("conclusionLevel", "SUPPORTED");
        ArrayNode references = args.putArray("evidenceIds");
        for (String id : ids) references.add(id);
        return args;
    }

    @Test
    void bothActualFailedInputsUseNoEntryIdAndCorrectedRealProbeAndChangeAreAccepted()
            throws Exception {
        for (AgentStore.Run run : failures()) {
            assertThrows(
                    AgentEvidenceRegistry.ReferenceError.class,
                    () ->
                            AgentEvidenceRegistry.validate(
                                    run, run.state().path("toolIntent").path("arguments")));
            assertEquals(10, run.state().path("evidenceRegistry").size());
            JsonNode probe = entry(run, "demo_target_inspect");
            JsonNode change = entry(run, "recent_changes");
            assertTrue(probe.path("usable").asBoolean());
            assertEquals("runtime-4690", change.path("sourceId").asText());
            assertTrue(change.path("usable").asBoolean());
            modern(run);
            ObjectNode args = supported(probe.path("id").asText(), change.path("id").asText());
            assertDoesNotThrow(
                    () -> AgentTools.validateForSnapshot(run, "ticket_add_analysis", args));
            assertDoesNotThrow(() -> AgentEvidenceRegistry.validate(run, args));
        }
    }

    @Test
    void inventedForeignContainerAndEmptyBodyReferencesCannotSupportDiagnosis() throws Exception {
        var runs = failures();
        var run = runs.get(0);
        modern(run);
        String actual = entry(run, "demo_target_inspect").path("id").asText();
        String foreign = entry(runs.get(1), "demo_target_inspect").path("id").asText();
        for (String invalid :
                List.of(
                        "ev-invented",
                        foreign,
                        run.state()
                                .path("observations")
                                .path("diagnose:1:4")
                                .path("evidenceBundleId")
                                .asText()))
            assertThrows(
                    AgentEvidenceRegistry.ReferenceError.class,
                    () -> AgentEvidenceRegistry.validate(run, supported(invalid)));
        ObjectNode textOnly = supported(actual).put("evidence", "根据经验，CPU过高导致故障；引用仅供查看");
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, textOnly));
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () ->
                        AgentEvidenceRegistry.validate(
                                run, supported(entry(run, "recent_changes").path("id").asText())));
        JsonNode missing =
                AgentEvidenceRegistry.currentEntries(run).stream()
                        .filter(e -> e.path("quality").asText().equals("NO_DATA"))
                        .findFirst()
                        .orElseThrow();
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, supported(missing.path("id").asText())));
    }

    @Test
    void tamperedSourceScopeAndFutureTimeAreRejectedWithoutRegisteringForeignFacts()
            throws Exception {
        var run = failures().get(0);
        modern(run);
        String id = entry(run, "demo_target_inspect").path("id").asText();
        ((ObjectNode) run.state().path("observations").path("diagnose:1:2")).put("redisPort", 1234);
        assertThrows(
                AgentEvidenceRegistry.ReferenceError.class,
                () -> AgentEvidenceRegistry.validate(run, supported(id)));
        for (String field : List.of("targetCode", "incidentId", "scope")) {
            var fresh = failures().get(0);
            ObjectNode result =
                    (ObjectNode) fresh.state().path("observations").path("diagnose:1:2");
            result.put(field, "foreign");
            AgentEvidenceRegistry.register(
                    fresh,
                    AgentJson.object().put("id", "diagnose:1:2").put("name", "demo_target_inspect"),
                    result);
            assertFalse(fresh.state().has("evidenceRegistry"));
        }
        var future = failures().get(0);
        ((ObjectNode) future.state().path("observations").path("diagnose:1:2"))
                .put("observedAt", "2030-01-01T00:00:00Z");
        assertFalse(entry(future, "demo_target_inspect").path("usable").asBoolean());
    }

    @Test
    void frozenOldSchemaRemainsTextualAndHistoricalDiagnosisIsNotRewritten() throws Exception {
        var run = failures().get(0);
        run.state().set("diagnosis", AgentJson.object().put("summary", "原历史诊断"));
        JsonNode original = run.snapshot().deepCopy();
        String id = entry(run, "demo_target_inspect").path("id").asText();
        ObjectNode args = supported(id);
        assertThrows(
                RuntimeException.class,
                () -> AgentTools.validateForSnapshot(run, "ticket_add_analysis", args));
        args.remove("evidenceIds");
        args.put("evidence", id + " httpStatus=503；REDIS_CONNECT_FAILED");
        assertDoesNotThrow(() -> AgentTools.validateForSnapshot(run, "ticket_add_analysis", args));
        assertDoesNotThrow(() -> AgentEvidenceRegistry.validate(run, args));
        assertEquals(original, run.snapshot());
        assertEquals("原历史诊断", run.state().path("diagnosis").path("summary").asText());
    }

    @Test
    void compressedActualRequestKeepsAllCitationMappingsAndAuditsExactlyWhatWasSent()
            throws Exception {
        var run = failures().get(0);
        modern(run);
        ObjectNode request =
                AgentJson.object().put("remainingTokens", 40000).put("maxOutputTokens", 4096);
        ArrayNode messages = request.putArray("messages");
        messages.add(AgentJson.object().put("role", "system").put("content", "固定权限"));
        messages.add(AgentJson.object().put("role", "tool").put("content", "无关历史正文".repeat(7000)));
        request.set("tools", run.snapshot().path("tools"));
        AgentEvidenceRegistry.attachMapping(run, request);
        String mapping = AgentEvidenceRegistry.mapping(run);
        assertTrue(AgentContext.fitNewRequest(request));
        ObjectNode audit = AgentEvidenceRegistry.sentMappingAudit(request);
        assertEquals(mapping, audit.path("mapping").asText());
        for (JsonNode e : AgentEvidenceRegistry.currentEntries(run))
            assertTrue(audit.path("mapping").asText().contains(e.path("id").asText()));
        assertEquals(AgentJson.hash(request), audit.path("requestHash").asText());
    }

    @Test
    void twoCorrectionChancesPreserveBudgetAndCancelUnexecutedBatchWithoutApprovals()
            throws Exception {
        var run = failures().get(0);
        // Include the tool answer from the knowledge batch so native IDs remain paired.
        for (JsonNode message : run.state().path("messages")) {
            if (message.path("tool_calls").isArray()
                    && message.path("tool_calls")
                            .path(0)
                            .path("id")
                            .asText()
                            .equals(
                                    run.state()
                                            .path("toolIntent")
                                            .path("providerCallId")
                                            .asText())) {
                ObjectNode repair =
                        ((ArrayNode) message.path("tool_calls"))
                                .addObject()
                                .put("id", "queued-repair")
                                .put("type", "function");
                repair.set(
                        "function",
                        AgentJson.object()
                                .put("name", "demo_config_restore")
                                .put("arguments", "{}"));
            }
        }
        ((ArrayNode) run.state().path("pendingCalls"))
                .add(
                        AgentJson.object()
                                .put("id", "later-repair")
                                .put("name", "demo_config_restore")
                                .put("providerCallId", "queued-repair"));
        JsonNode originalCall = run.state().path("toolIntent").deepCopy();
        int tokens = run.state().path("tokens").asInt();
        String deadline = run.state().path("deadline").asText();
        assertTrue(AgentReferenceCorrections.eligible(run));
        ObjectNode audit = AgentReferenceCorrections.correct(run, "invalid ID");
        assertNotNull(audit);
        assertEquals(2, audit.path("unexecutedCalls").size());
        assertFalse(run.state().has("toolIntent"));
        assertTrue(run.state().path("pendingCalls").isEmpty());
        for (int attempt = 2; attempt <= 3; attempt++) {
            installCall(run, originalCall.deepCopy());
            ObjectNode next = AgentReferenceCorrections.correct(run, "still invalid");
            if (attempt == 2) assertNotNull(next);
            else assertNull(next);
        }
        assertEquals(2, run.state().path("referenceCorrections").asInt());
        assertEquals(tokens, run.state().path("tokens").asInt());
        assertEquals(deadline, run.state().path("deadline").asText());
    }

    private static void installCall(AgentStore.Run run, JsonNode call) {
        run.state().set("toolIntent", call);
        run.state().putArray("pendingCalls").add(call.deepCopy());
        ObjectNode message = AgentJson.object().put("role", "assistant");
        message.putArray("tool_calls")
                .add(AgentJson.object().put("id", call.path("providerCallId").asText()));
        ((ArrayNode) run.state().path("messages")).add(message);
    }

    @Test
    void preparedWritesAndUnknownModelOutcomesNeverEnterCorrection() throws Exception {
        var run = failures().get(0);
        ((ObjectNode) run.state().path("toolIntent"))
                .set("preparedRequest", AgentJson.object().put("body", "already prepared"));
        assertFalse(AgentReferenceCorrections.eligible(run));
        assertNull(AgentReferenceCorrections.correct(run, "invalid"));
        var unknown = failures().get(0);
        unknown.state().set("modelIntent", AgentJson.object().put("callId", "unknown"));
        assertFalse(AgentReferenceCorrections.eligible(unknown));
        assertNull(AgentReferenceCorrections.correct(unknown, "invalid"));
    }

    @Test
    void runtimeCorrectsBeforeAnyWriteAndChargesAllModelTurnsToOriginalBudget() {
        var clients =
                new AgentTestSupport.FakeClients() {
                    @Override
                    JsonNode call(
                            String audience,
                            String path,
                            String method,
                            JsonNode body,
                            com.opsagent.common.security.InternalActorTokens.Context actor) {
                        if (path.endsWith("/snapshot")) {
                            ObjectNode result =
                                    AgentJson.object()
                                            .put("targetCode", AgentTargets.ORDER)
                                            .put("incidentId", "incident-1")
                                            .put("scope", "ISOLATED_DEMO")
                                            .put("observedAt", Instant.now().toString())
                                            .put("redisPort", 6380);
                            result.set(
                                    "business",
                                    AgentJson.object()
                                            .put("httpStatus", 503)
                                            .put("reasonCode", "REDIS_CONNECT_FAILED"));
                            return result;
                        }
                        return super.call(audience, path, method, body, actor);
                    }
                };
        var fixture = new AgentTestSupport(clients);
        clients.model =
                request -> {
                    int turn = clients.modelRequests.size();
                    if (turn == 1)
                        return AgentTestSupport.response("demo_target_inspect", AgentJson.object());
                    if (turn == 2) {
                        ObjectNode result =
                                (ObjectNode)
                                        AgentTestSupport.response(
                                                "ticket_add_analysis",
                                                supported("bundle-not-entry"));
                        ObjectNode repair =
                                ((ArrayNode) result.path("assistantMessage").path("tool_calls"))
                                        .addObject()
                                        .put("id", "repair-after-invalid-analysis")
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
                        return result;
                    }
                    var matcher =
                            java.util.regex.Pattern.compile("ev-[a-f0-9]{24}")
                                    .matcher(request.toString());
                    assertTrue(matcher.find());
                    return AgentTestSupport.response(
                            "ticket_add_analysis", supported(matcher.group()));
                };
        String id = fixture.create("citation-correction", -1);
        ObjectNode state = fixture.store.get(id).state();
        state.put("targetCode", AgentTargets.ORDER).put("tokenBudget", 40000);
        fixture.jdbc.update("UPDATE agent_run SET state_json=? WHERE id=?", state.toString(), id);
        for (int i = 0; i < 24 && clients.writes.isEmpty(); i++) fixture.step();
        assertEquals(1, clients.writes.size());
        var done = fixture.store.get(id);
        assertEquals(1, done.state().path("referenceCorrections").asInt());
        assertEquals(30, done.state().path("tokens").asInt());
        assertEquals(40000, done.state().path("tokenBudget").asInt());
        assertEquals(0, clients.actions);
        assertEquals(0, fixture.store.approvals(id).size());
        assertEquals(1, done.state().path("diagnosis").path("evidenceIds").size());
        assertFalse(done.state().has("referenceCorrectionPending"));
        assertTrue(clients.writes.get(0).path("input").path("evidence").asText().length() <= 1000);
        assertEquals(
                3,
                fixture.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_event WHERE run_id=? AND"
                            + " event_type='MODEL_INTENT' AND payload_json LIKE '%mappingHash%'",
                        Integer.class, id));
    }

    @Test
    void correctionExhaustionCannotBeResetByResumeOrReplayTheBadIntent() {
        var fixture = new AgentTestSupport();
        fixture.clients.model =
                request -> AgentTestSupport.response("ticket_add_analysis", supported("bundle-id"));
        String id = fixture.create("repeated-invalid-reference", -1);
        for (int i = 0; i < 30 && fixture.store.get(id).status().equals("QUEUED"); i++)
            fixture.step();
        var stopped = fixture.store.get(id);
        assertEquals("NEEDS_ATTENTION", stopped.status());
        assertEquals(2, stopped.state().path("referenceCorrections").asInt());
        assertEquals(3, fixture.clients.modelRequests.size());
        assertTrue(stopped.state().path("referenceFailure").path("exhausted").asBoolean());
        assertFalse(stopped.state().path("toolIntent").has("preparedRequest"));
        assertThrows(RuntimeException.class, () -> fixture.store.resume(id, -1));
        assertEquals(stopped.state(), fixture.store.get(id).state());
        assertEquals(0, fixture.clients.writes.size());
    }

    @Test
    void legacyBundleOnlyFailureResumesWithExplicitFeedbackRatherThanReplayingTheWrite()
            throws Exception {
        for (var run : failures()) {
            run.state().put("message", "证据支持结论必须引用本运行后端证据包中的实际证据ID");
            ObjectNode audit = AgentReferenceCorrections.resume(run);
            assertNotNull(audit);
            assertTrue(audit.path("legacyResume").asBoolean());
            assertFalse(run.state().has("toolIntent"));
            assertEquals(1, run.state().path("referenceCorrections").asInt());
            JsonNode feedback =
                    run.state().path("messages").path(run.state().path("messages").size() - 1);
            assertEquals("tool", feedback.path("role").asText());
            assertTrue(feedback.path("content").asText().contains("bundle ID"));
        }
    }

    @Test
    void retryCannotSkipCorrectedDiagnosisAndRequestANewRepairApproval() {
        var fixture = new AgentTestSupport();
        fixture.clients.model =
                request ->
                        fixture.clients.modelRequests.size() == 1
                                ? AgentTestSupport.response(
                                        "ticket_add_analysis", supported("bad-bundle-id"))
                                : AgentTestSupport.response(
                                        "demo_config_restore",
                                        AgentJson.object().put("expectedRevision", "a".repeat(64)));
        String id = fixture.create("correction-cannot-skip-diagnosis", -1);
        for (int i = 0; i < 24 && fixture.store.get(id).status().equals("QUEUED"); i++)
            fixture.step();
        var stopped = fixture.store.get(id);
        assertEquals("NEEDS_ATTENTION", stopped.status());
        assertTrue(stopped.state().path("referenceCorrectionPending").asBoolean());
        assertEquals(1, stopped.state().path("referenceCorrections").asInt());
        assertEquals(0, fixture.store.approvals(id).size());
        assertEquals(0, fixture.clients.actions);
        assertEquals(0, fixture.clients.writes.size());
        assertTrue(stopped.state().path("message").asText().contains("不能创建新的修复审批"));
    }
}
