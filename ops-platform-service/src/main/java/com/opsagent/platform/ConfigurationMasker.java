package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Configuration response boundary: fail closed on unparseable content.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class ConfigurationMasker {
    static final String MASK = "******";
    private static final Pattern SECRET =
            Pattern.compile(
                    "(?i).*(password|passwd|secret|token|credential|privatekey|apikey|accesskey"
                            + "|authorization|identityvalue|identitykey).*");
    private static final Pattern URL_AUTH = Pattern.compile("(://)[^/@\\s]+@");
    private static final Pattern URL_SECRET =
            Pattern.compile(
                    "(?i)([?&;](?:password|pwd|token|apikey|api_key|access_token|secret)=)[^&#;\\s]*");
    private static final Pattern CREDENTIAL_ASSIGNMENT =
            Pattern.compile(
                    "(?i)(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|authorization)\\s*[=:]");
    private final ObjectMapper json;

    ConfigurationMasker(ObjectMapper json) {
        this.json = json;
    }

    String mask(String content, String type) {
        if (content == null || content.isBlank()) return "";
        if (content.length() > 262144) return MASK + "（正文超过安全预览上限）";
        try {
            JsonNode root;
            String format = type == null ? "" : type.toLowerCase(Locale.ROOT);
            if (format.equals("json")
                    || content.stripLeading().startsWith("{")
                    || content.stripLeading().startsWith("[")) {
                root = json.readTree(content);
            } else if (format.equals("properties")) {
                Properties properties = new Properties();
                properties.load(new StringReader(content));
                root = json.valueToTree(properties);
            } else if (format.equals("yaml") || format.equals("yml")) {
                LoaderOptions options = new LoaderOptions();
                options.setMaxAliasesForCollections(10);
                options.setCodePointLimit(262144);
                options.setNestingDepthLimit(40);
                Object parsed = new Yaml(new SafeConstructor(options)).load(content);
                root = json.valueToTree(parsed);
            } else return MASK + "（此格式不提供明文预览）";
            if (root == null || (!root.isContainerNode())) return MASK;
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(sanitize(root, 0));
        } catch (Exception ignored) {
            return MASK + "（无法安全解析，正文已隐藏）";
        }
    }

    private JsonNode sanitize(JsonNode value, int depth) {
        if (depth > 40) return json.getNodeFactory().textNode(MASK);
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
            boolean namedSecret =
                    sensitive(value.path("name").asText()) || sensitive(value.path("key").asText());
            value.fields()
                    .forEachRemaining(
                            entry -> {
                                result.set(
                                        entry.getKey(),
                                        sensitive(entry.getKey())
                                                        || namedSecret
                                                                && !List.of(
                                                                                "name",
                                                                                "key",
                                                                                "description",
                                                                                "type")
                                                                        .contains(entry.getKey())
                                                ? json.getNodeFactory().textNode(MASK)
                                                : sanitize(entry.getValue(), depth + 1));
                            });
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode();
            value.forEach(item -> result.add(sanitize(item, depth + 1)));
            return result;
        }
        if (value.isTextual()) {
            String text = value.textValue();
            if (text.contains("-----BEGIN")
                    || text.matches("(?is).*(?:Bearer |Basic |eyJ[a-zA-Z0-9_-]+\\.).*"))
                return json.getNodeFactory().textNode(MASK);
            // Nested serialized objects are common in Nacos. Parse and mask them too.
            if (text.stripLeading().startsWith("{") || text.stripLeading().startsWith("[")) {
                try {
                    return json.getNodeFactory()
                            .textNode(
                                    json.writeValueAsString(
                                            sanitize(json.readTree(text), depth + 1)));
                } catch (Exception ignored) {
                    return json.getNodeFactory().textNode(MASK);
                }
            }
            if (text.contains("\n")) return json.getNodeFactory().textNode(MASK);
            text = URL_AUTH.matcher(text).replaceAll("$1" + MASK + "@");
            text = URL_SECRET.matcher(text).replaceAll("$1" + MASK);
            if (CREDENTIAL_ASSIGNMENT.matcher(text).find())
                return json.getNodeFactory().textNode(MASK);
            return json.getNodeFactory().textNode(text);
        }
        return value.deepCopy();
    }

    private static boolean sensitive(String name) {
        String normalized = name.replaceAll("[^a-zA-Z]", "");
        return SECRET.matcher(normalized).matches() || normalized.equalsIgnoreCase("pwd");
    }
}
