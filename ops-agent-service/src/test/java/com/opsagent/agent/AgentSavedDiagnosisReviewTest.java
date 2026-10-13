package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 显式环境门控的保存诊断验收：只生成请求或校验已保存计划，没有网络客户端或业务写入。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentSavedDiagnosisReviewTest {
    @Test
    void savedDiagnosisPlanUsesTheActualValidatorWithoutExecutingOrResumingTheRun()
            throws Exception {
        String directory = System.getenv("OPS_AGENT_DIAGNOSIS_REVIEW_DIRECTORY");
        assumeTrue(directory != null && !directory.isBlank());
        Path base = Path.of(directory).toAbsolutePath();
        String suffix = System.getenv("OPS_AGENT_DIAGNOSIS_REVIEW_ATTEMPT_SUFFIX");
        if (suffix == null) suffix = "";
        assertTrue(suffix.matches("(?:-attempt[2-9])?"));
        JsonNode row =
                AgentJson.MAPPER.readTree(base.resolve("failed-run-raw-private.json").toFile());
        ObjectNode state = (ObjectNode) AgentJson.read(row.path("stateRaw").asText());
        var createdAt =
                LocalDateTime.parse(row.path("createdAt").asText().replace(' ', 'T'))
                        .toInstant(ZoneOffset.ofHours(8));
        var run =
                new AgentStore.Run(
                        row.path("id").asText(),
                        row.path("ownerId").asLong(),
                        row.path("status").asText(),
                        row.path("nodeId").asText(),
                        (ObjectNode) AgentJson.read(row.path("snapshotRaw").asText()),
                        state,
                        row.path("version").asLong(),
                        false,
                        false,
                        createdAt);
        String originalState = state.toString();
        Path responseFile = base.resolve("model-replay-response" + suffix + ".json");
        if (!Files.exists(responseFile)) {
            ObjectNode request = request(run);
            int remaining = AgentRuntime.modelRequestCapacity(state);
            int estimatedInput = request.toString().getBytes(StandardCharsets.UTF_8).length;
            assertTrue(
                    (long) estimatedInput + request.path("max_tokens").asInt() <= remaining,
                    "The conservative request size must fit the original remaining budget");
            ObjectNode meta =
                    AgentJson.object()
                            .put("originalRunId", run.id())
                            .put("originalTokensCharged", state.path("tokens").asInt())
                            .put("originalTokenBudget", state.path("tokenBudget").asInt())
                            .put("remainingTokens", remaining)
                            .put("estimatedInputTokens", estimatedInput)
                            .put(
                                    "estimationMethod",
                                    "UTF-8 full request byte upper bound, not billed usage")
                            .put("maxOutputTokens", request.path("max_tokens").asInt())
                            .put("requestHash", AgentJson.hash(request))
                            .put("reviewOnly", true)
                            .put("businessWrites", 0)
                            .put("originalRunResumed", false);
            meta.put("protocol", "NativeToolModelClient: auto tool_choice + thinking disabled");
            writeNew(base.resolve("model-replay-request" + suffix + ".json"), request);
            writeNew(base.resolve("model-replay-meta" + suffix + ".json"), meta);
        } else {
            JsonNode response = AgentJson.MAPPER.readTree(responseFile.toFile());
            JsonNode choice = response.path("choices").path(0);
            JsonNode calls = choice.path("message").path("tool_calls");
            ObjectNode receipt =
                    AgentJson.object()
                            .put("originalRunId", run.id())
                            .put("originalRunResumed", false)
                            .put("businessWrites", 0)
                            .put(
                                    "actualValidator",
                                    "AgentTools.validateForSnapshot +"
                                            + " AgentEvidenceRegistry.validate")
                            .put("responseHash", AgentJson.hash(response));
            receipt.set("usage", response.path("usage"));
            ArrayNode failures = receipt.putArray("failures");
            if (!"tool_calls".equals(choice.path("finish_reason").asText()))
                failures.add("Provider did not finish a complete tool plan");
            if (!calls.isArray() || calls.size() != 1)
                failures.add("Expected exactly one diagnosis plan");
            for (JsonNode call : calls) {
                String name = call.path("function").path("name").asText();
                if (!"ticket_add_analysis".equals(name)) {
                    failures.add("Only a diagnosis plan may be reviewed");
                    continue;
                }
                JsonNode args = call.path("function").path("arguments");
                try {
                    if (args.isTextual()) args = AgentJson.read(args.asText());
                    AgentTools.validateForSnapshot(run, name, args);
                    AgentEvidenceRegistry.validate(run, args);
                } catch (RuntimeException failure) {
                    failures.add(failure.getMessage());
                }
            }
            receipt.put("status", failures.isEmpty() ? "PASS" : "FAIL");
            String output = System.getenv("OPS_AGENT_DIAGNOSIS_REVIEW_RECEIPT_DIRECTORY");
            assertNotNull(output);
            writeNew(Path.of(output).resolve("model-replay-validator" + suffix + ".json"), receipt);
            assertTrue(failures.isEmpty(), failures.toString());
        }
        assertTrue(originalState.equals(state.toString()), "Saved run state must remain unchanged");
    }

    private ObjectNode request(AgentStore.Run run) {
        ObjectNode request =
                AgentJson.object()
                        .put("model", run.snapshot().path("model").path("model").asText())
                        .put("max_tokens", 4096)
                        .put("stream", false)
                        .put("parallel_tool_calls", false);
        ArrayNode messages = run.state().path("messages").deepCopy();
        String guidance =
                AgentMetricCorrectionFeedback.build(
                        run, run.state().path("toolIntent").path("arguments"));
        for (JsonNode pending : run.state().path("pendingCalls")) {
            messages.add(
                    AgentJson.object()
                            .put("role", "tool")
                            .put("tool_call_id", pending.path("providerCallId").asText())
                            .put(
                                    "content",
                                    "保存运行的离线诊断验收：原诊断未写入，原运行保持停止。"
                                            + "这是一次独立的计划校验，只能根据同一保存采样修正诊断，不执行工具、不宣称当前已恢复。\n"
                                            + guidance));
        }
        messages.add(
                AgentJson.object()
                        .put("role", "user")
                        .put(
                                "content",
                                "仅返回一个修订后的 ticket_add_analysis 计划供离线校验。"
                                        + "所有数值事实逐项独立一行，完整引用原 ev- ID；未知保持未知。"
                                        + "采样不是当前状态；文档示例不能证明此事件端口一定无监听。"
                                        + "没有直接测量的排除结论也应保留为待验证，不要求或执行修复。"));
        request.set("messages", messages);
        AgentEvidenceRegistry.attachMapping(run, request);
        ArrayNode tools = request.putArray("tools");
        for (JsonNode tool : run.snapshot().path("tools"))
            if ("ticket_add_analysis".equals(tool.path("function").path("name").asText()))
                tools.add(tool.deepCopy());
        assertEquals(1, tools.size());
        request.put("tool_choice", "auto");
        request.putObject("thinking").put("type", "disabled");
        return request;
    }

    private void writeNew(Path path, JsonNode value) throws Exception {
        Files.writeString(
                path,
                AgentJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }
}
