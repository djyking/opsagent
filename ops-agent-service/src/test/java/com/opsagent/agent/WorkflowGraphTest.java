package com.opsagent.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;

import org.junit.jupiter.api.Test;

/**
 * @author heyu
 */
class WorkflowGraphTest {
    @Test
    void publishedBuiltinIsValidButUnsupportedConfigurationIsRejected() {
        ObjectNode graph = WorkflowGraph.builtin();
        assertDoesNotThrow(() -> WorkflowGraph.validate(graph));
        ((ObjectNode) graph.path("nodes").get(1).path("config")).put("command", "arbitrary-shell");
        assertThrows(BusinessException.class, () -> WorkflowGraph.validate(graph));
    }

    @Test
    void graphCannotReferenceActorStateOrRunArbitraryTool() {
        ObjectNode graph = WorkflowGraph.builtin();
        ObjectNode node = (ObjectNode) graph.path("nodes").get(1);
        node.put("type", "TOOL");
        node.set(
                "config",
                AgentJson.read(
                        """
                        {"tool":"knowledge_search","arguments":{"query":{"$ref":"/actor/userId"}}}
                        """));
        assertThrows(BusinessException.class, () -> WorkflowGraph.validate(graph));
        node.set(
                "config",
                AgentJson.read(
                        """
                        {"tool":"arbitrary_command","arguments":{}}
                        """));
        assertThrows(BusinessException.class, () -> WorkflowGraph.validate(graph));
    }
}
