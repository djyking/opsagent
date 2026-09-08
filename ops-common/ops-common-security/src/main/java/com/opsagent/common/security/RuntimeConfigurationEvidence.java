package com.opsagent.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.ConfigurationExecutionEvidence;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Fixed managed-field evidence without credential values, command-line values or file paths.
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class RuntimeConfigurationEvidence {
    private final ConfigurableEnvironment environment;
    private final ConfigurationExecutionEvidence evidence;

    public RuntimeConfigurationEvidence(ConfigurableEnvironment environment) {
        this.environment = environment;
        evidence =
                new ConfigurationExecutionEvidence(
                        environment.getProperty("spring.application.name", "unknown"),
                        environment.getProperty("ops.configuration.executor.secret", ""),
                        environment.getProperty("ops.configuration.managed-files", ""),
                        environment.getProperty("ops.configuration.managed-keys", ""),
                        this::sourcePresent,
                        environment.getProperty("ops.configuration.executor.trusted-host", ""));
    }

    private boolean sourcePresent(Path path) {
        String name = path.getFileName().toString();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getName().contains(name)
                    && source.getName().toLowerCase(Locale.ROOT).contains("config")) return true;
        }
        return false;
    }

    public boolean authorized(String remote, String stamp, String nonce, String signature) {
        return evidence.authorized(remote, stamp, nonce, signature);
    }

    public ConfigurationExecutionEvidence.Snapshot snapshot() {
        List<ConfigurationExecutionEvidence.Field> fields = new ArrayList<>();
        for (String key : evidence.keys()) {
            try {
                String value = value(key);
                if (value == null) continue;
                boolean overridden = false;
                String origin = "运行配置源";
                for (PropertySource<?> source : environment.getPropertySources()) {
                    if (source.getName().equals("configurationProperties")
                            || !source.containsProperty(key)) continue;
                    String name = source.getName().toLowerCase(Locale.ROOT);
                    overridden =
                            name.contains("systemenvironment")
                                    || name.contains("systemproperties")
                                    || name.contains("commandline");
                    Object raw = source.getProperty(key);
                    if (raw instanceof String text && text.contains("${")) {
                        var matches =
                                Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)(?::[^}]*)?}")
                                        .matcher(text);
                        while (matches.find()) {
                            String reference = matches.group(1);
                            for (PropertySource<?> nested : environment.getPropertySources()) {
                                String nestedName = nested.getName().toLowerCase(Locale.ROOT);
                                if ((nestedName.contains("systemenvironment")
                                                || nestedName.contains("systemproperties")
                                                || nestedName.contains("commandline"))
                                        && nested.containsProperty(reference)) overridden = true;
                            }
                        }
                    }
                    origin = overridden ? "环境变量或启动参数覆盖" : "配置文件解析值";
                    break;
                }
                fields.add(
                        new ConfigurationExecutionEvidence.Field(
                                key,
                                ConfigurationExecutionEvidence.sensitiveKey(key)
                                        ? null
                                        : RuntimeConfigurationSnapshot.safeValue(value),
                                evidence.hash(value),
                                overridden,
                                origin));
            } catch (Exception ignored) {
                // Missing/unresolved fields remain absent and fail the executor's adoption check.
            }
        }
        return evidence.snapshot(fields);
    }

    private String value(String key) throws Exception {
        String direct = environment.getProperty(key);
        if (direct != null) return direct;
        TreeMap<String, String> values = new TreeMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String name : enumerable.getPropertyNames()) {
                if (!name.startsWith(key + ".") && !name.startsWith(key + "[")) continue;
                String value = environment.getProperty(name);
                if (value == null) continue;
                String relative = name.substring(key.length());
                if (relative.startsWith(".")) relative = relative.substring(1);
                values.put(relative, value);
            }
        }
        return values.isEmpty() ? null : new ObjectMapper().writeValueAsString(values);
    }
}
