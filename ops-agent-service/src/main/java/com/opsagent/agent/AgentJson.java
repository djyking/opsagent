package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 持久化定义和执行证据的统一 JSON 编解码。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AgentJson {
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private AgentJson() {}

    static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "无效 JSON");
        }
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    static String hash(JsonNode value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
