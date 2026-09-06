package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 有界无环工作流校验器；条件只读取节点结构化输出，不执行表达式代码。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class WorkflowGraph {
    static final Set<String> TYPES =
            Set.of(
                    "START",
                    "END",
                    "AGENT",
                    "TOOL",
                    "LLM",
                    "RAG",
                    "CONDITION",
                    "SET_VARIABLE",
                    "APPROVAL",
                    "HUMAN_INPUT");

    private WorkflowGraph() {}

    static void validate(JsonNode graph) {
        JsonNode nodes = graph.path("nodes");
        JsonNode edges = graph.path("edges");
        if (!nodes.isArray()
                || nodes.size() < 2
                || nodes.size() > 30
                || !edges.isArray()
                || edges.size() > 60
                || graph.toString().length() > 64000) throw AgentJson.invalid("节点/边超出范围");
        Map<String, JsonNode> indexed = new HashMap<>();
        Map<String, Integer> degrees = new HashMap<>();
        int starts = 0;
        int ends = 0;
        for (JsonNode node : nodes) {
            String id = node.path("id").asText();
            String type = node.path("type").asText();
            if (!id.matches("[a-zA-Z][a-zA-Z0-9_-]{0,60}")
                    || !TYPES.contains(type)
                    || indexed.put(id, node) != null) throw AgentJson.invalid("节点 ID 重复或类型不支持");
            degrees.put(id, 0);
            if (type.equals("START")) starts++;
            if (type.equals("END")) ends++;
            if (type.equals("TOOL")
                    && !AgentTools.NAMES.contains(node.path("config").path("tool").asText())) {
                throw AgentJson.invalid("工具不在注册表内");
            }
            if (node.path("config").path("prompt").asText().length() > 6000) {
                throw AgentJson.invalid("节点提示词过长");
            }
            validateConfiguration(type, node.path("config"));
        }
        if (starts != 1 || ends < 1) throw AgentJson.invalid("需要一个开始节点和至少一个结束节点");
        Set<String> uniqueEdges = new HashSet<>();
        for (JsonNode edge : edges) {
            String from = edge.path("from").asText();
            String to = edge.path("to").asText();
            if (!indexed.containsKey(from)
                    || !indexed.containsKey(to)
                    || from.equals(to)
                    || !uniqueEdges.add(from + ":" + to)) throw AgentJson.invalid("无效或重复连线");
            degrees.compute(to, (key, value) -> value + 1);
        }
        for (JsonNode node : nodes) {
            String id = node.path("id").asText();
            long outgoing =
                    java.util.stream.StreamSupport.stream(edges.spliterator(), false)
                            .filter(e -> e.path("from").asText().equals(id))
                            .count();
            String type = node.path("type").asText();
            if (type.equals("START") ? degrees.get(id) != 0 : degrees.get(id) == 0) {
                throw AgentJson.invalid("存在不可达节点");
            }
            if (type.equals("END")
                    ? outgoing != 0
                    : type.equals("CONDITION") ? outgoing != 2 : outgoing != 1) {
                throw AgentJson.invalid("普通节点需一条出口，条件节点需两条出口，结束节点不能有出口");
            }
            if (type.equals("CONDITION")) {
                Set<String> branches = new HashSet<>();
                edges.forEach(
                        e -> {
                            if (e.path("from").asText().equals(id))
                                branches.add(e.path("when").asText());
                        });
                if (!branches.equals(Set.of("true", "false")))
                    throw AgentJson.invalid("条件出口需 true/false");
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        degrees.forEach(
                (id, degree) -> {
                    if (degree == 0) ready.add(id);
                });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.remove();
            visited++;
            for (JsonNode edge : edges) {
                if (edge.path("from").asText().equals(id)) {
                    String to = edge.path("to").asText();
                    if (degrees.compute(to, (key, value) -> value - 1) == 0) ready.add(to);
                }
            }
        }
        if (visited != nodes.size()) throw AgentJson.invalid("不允许图循环；模型迭代在 Agent 节点预算内进行");
    }

    static JsonNode node(JsonNode graph, String id) {
        for (JsonNode node : graph.path("nodes"))
            if (node.path("id").asText().equals(id)) return node;
        throw AgentJson.invalid("节点不存在");
    }

    private static void validateConfiguration(String type, JsonNode config) {
        Set<String> allowed =
                switch (type) {
                    case "AGENT", "LLM", "APPROVAL", "HUMAN_INPUT" -> Set.of("prompt");
                    case "TOOL" -> Set.of("tool", "arguments");
                    case "RAG" -> Set.of("query");
                    case "CONDITION" -> Set.of("path", "equals");
                    case "SET_VARIABLE" -> Set.of("key", "value");
                    default -> Set.of();
                };
        if (!config.isMissingNode() && !config.isObject()) throw AgentJson.invalid("节点配置必须为对象");
        config.fieldNames()
                .forEachRemaining(
                        key -> {
                            if (!allowed.contains(key)) throw AgentJson.invalid("节点包含未实现或不允许的配置字段");
                        });
        if (config.has("prompt") && !config.path("prompt").isTextual())
            throw AgentJson.invalid("提示词必须是文本");
        if (type.equals("RAG")
                && (!config.path("query").isTextual()
                        || config.path("query").asText().isBlank()
                        || config.path("query").asText().length() > 2000)) {
            throw AgentJson.invalid("检索词为空或超出范围");
        }
        if (type.equals("CONDITION")) {
            String path = config.path("path").asText();
            if ((!path.startsWith("/outputs/") && !path.startsWith("/variables/"))
                    || !config.has("equals")) {
                throw AgentJson.invalid("条件只能比较节点输出或变量");
            }
        }
        if (type.equals("SET_VARIABLE")
                && (!config.path("key").asText().matches("[a-zA-Z][a-zA-Z0-9_]{0,40}")
                        || !config.has("value"))) throw AgentJson.invalid("变量配置无效");
        if (type.equals("TOOL")) validateToolArguments(config);
    }

    private static void validateToolArguments(JsonNode config) {
        String name = config.path("tool").asText();
        JsonNode args =
                config.path("arguments").isMissingNode()
                        ? AgentJson.object()
                        : config.path("arguments");
        if (!args.isObject()) throw AgentJson.invalid("工具参数必须为对象");
        ObjectNode literals = (ObjectNode) args.deepCopy();
        args.fields()
                .forEachRemaining(
                        entry -> {
                            JsonNode value = entry.getValue();
                            if (value.isObject()) {
                                if (value.size() != 1
                                        || !value.path("$ref").asText().startsWith("/outputs/")) {
                                    throw AgentJson.invalid("动态工具参数只能引用节点输出");
                                }
                                literals.put(
                                        entry.getKey(),
                                        entry.getKey().equals("expectedRevision")
                                                        || entry.getKey().equals("immutableDigest")
                                                ? "a".repeat(64)
                                                : entry.getKey().equals("proposalId")
                                                        ? "00000000-0000-0000-0000-000000000000"
                                                        : "reference");
                            }
                        });
        AgentTools.validate(name, literals);
    }

    static String start(JsonNode graph) {
        for (JsonNode node : graph.path("nodes")) {
            if (node.path("type").asText().equals("START")) return node.path("id").asText();
        }
        throw AgentJson.invalid("缺少开始节点");
    }

    static String next(JsonNode graph, String id, String branch) {
        for (JsonNode edge : graph.path("edges")) {
            if (edge.path("from").asText().equals(id)
                    && (branch == null || edge.path("when").asText().equals(branch))) {
                return edge.path("to").asText();
            }
        }
        throw AgentJson.invalid("找不到下一节点");
    }

    static ObjectNode builtin() {
        return (ObjectNode)
                AgentJson.read(
                        """
       {"nodes":[
         {"id":"start","type":"START","label":"监控事件"},
         {"id":"diagnose","type":"AGENT","label":"AI 诊断与处置","config":{
           "prompt":"先读工单和目标证据，诊断真实原因。需要修复时选择匹配的固定修复工具。修复后再探测；仅证据允许时解决工单。最后用中文总结原因、动作和验证结果。"}},
 {"id":"verify","type":"TOOL","label":"验证恢复与工单收口","config":{
   "tool":"ticket_resolve","arguments":{"comment":"系统根据新鲜业务探针和告警恢复事件验证工单收口"}}},
 {"id":"end","type":"END","label":"记录结果"}],
"edges":[{"from":"start","to":"diagnose"},{"from":"diagnose","to":"verify"},
  {"from":"verify","to":"end"}]}
""");
    }

    static ObjectNode configurationChange() {
        return (ObjectNode)
                AgentJson.read(
                        """
                        {"nodes":[{"id":"start","type":"START","label":"已校验配置提案"},
                        {"id":"apply","type":"TOOL","label":"审批并执行精确配置变更","config":{
                        "tool":"config_change_apply","arguments":{
                        "proposalId":{"$ref":"/outputs/proposal/proposalId"},
                        "immutableDigest":{"$ref":"/outputs/proposal/immutableDigest"}}}},
                        {"id":"end","type":"END","label":"保留源发布及实例应用结果"}],
                        "edges":[{"from":"start","to":"apply"},{"from":"apply","to":"end"}]}
                        """);
    }
}
