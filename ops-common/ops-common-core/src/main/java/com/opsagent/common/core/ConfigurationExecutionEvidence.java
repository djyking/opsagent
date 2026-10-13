package com.opsagent.common.core;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Framework-neutral process evidence authenticated independently of restarting Auth and Gateway.
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class ConfigurationExecutionEvidence {
    public static final String PATH = "/internal/runtime/configuration-evidence";

    public record FileVersion(String fileName, String loadedVersion, boolean sourcePresent) {}

    public record Field(
            String key, String value, String valueDigest, boolean overridden, String source) {}

    public record Snapshot(
            String serviceId,
            String instanceId,
            long pid,
            Instant startedAt,
            Instant observedAt,
            List<FileVersion> configurationFiles,
            List<Field> fields) {}

    private final String service;
    private final String secret;
    private final String trustedHost;
    private final String instance = UUID.randomUUID().toString();
    private final List<FileVersion> versions;
    private final List<String> keys;
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();

    public ConfigurationExecutionEvidence(
            String service,
            String secret,
            String files,
            String keys,
            Function<Path, Boolean> sourcePresent) {
        this(service, secret, files, keys, sourcePresent, "");
    }

    public ConfigurationExecutionEvidence(
            String service,
            String secret,
            String files,
            String keys,
            Function<Path, Boolean> sourcePresent,
            String trustedHost) {
        this.service = service.matches("[a-zA-Z0-9_.-]{1,64}") ? service : "unknown";
        this.secret = secret;
        this.trustedHost = privateAddress(trustedHost) ? trustedHost : "";
        this.keys =
                java.util.Arrays.stream(keys.split(","))
                        .map(String::trim)
                        .filter(key -> key.matches("[a-zA-Z0-9_.\\[\\]-]{1,160}"))
                        .distinct()
                        .limit(300)
                        .toList();
        List<FileVersion> captured = new ArrayList<>();
        for (String file : files.split(",")) {
            if (file.isBlank()) continue;
            try {
                Path path = Path.of(file.trim()).toAbsolutePath().normalize();
                boolean present = sourcePresent.apply(path);
                String version =
                        present && Files.size(path) <= 1048576
                                ? HexFormat.of()
                                        .formatHex(
                                                MessageDigest.getInstance("SHA-256")
                                                        .digest(Files.readAllBytes(path)))
                                : "";
                captured.add(new FileVersion(path.getFileName().toString(), version, present));
            } catch (Exception ignored) {
                // Missing files never acquire a made-up loaded version.
            }
        }
        this.versions = List.copyOf(captured);
    }

    public List<String> keys() {
        return keys;
    }

    public boolean authorized(String remoteAddress, String stamp, String nonce, String signature) {
        if (secret.length() < 32
                || (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(remoteAddress)
                        && (trustedHost.isEmpty() || !trustedHost.equals(remoteAddress)))
                || stamp == null
                || nonce == null
                || signature == null
                || !nonce.matches("[a-f0-9]{32}")
                || !signature.matches("[a-f0-9]{64}")) return false;
        try {
            long epoch = Long.parseLong(stamp);
            long current = Instant.now().getEpochSecond();
            if (Math.abs(current - epoch) > 30) return false;
            String expected = hash(stamp + "\n" + nonce + "\nGET\n" + PATH);
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII))) return false;
            nonces.entrySet().removeIf(entry -> entry.getValue() < current - 60);
            return nonces.putIfAbsent(nonce, epoch) == null;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return "";
        }
    }

    public static boolean sensitiveKey(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*(token[-_.]?(budget|count|ttl)|(max|context|output)[-_.].*tokens$).*"))
            return lower.matches(
                    ".*(password|passwd|secret|api[-_]?key|credential|private[-_]?key).*");
        return lower.matches(
                ".*(password|passwd|secret|token|api[-_]?key|credential|private[-_]?key).*");
    }

    public static boolean privateAddress(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))
            return false;
        int[] parts =
                java.util.Arrays.stream(value.split("\\.")).mapToInt(Integer::parseInt).toArray();
        if (java.util.Arrays.stream(parts).anyMatch(part -> part > 255)) return false;
        return parts[0] == 10
                || parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31
                || parts[0] == 192 && parts[1] == 168;
    }

    public Snapshot snapshot(List<Field> fields) {
        return new Snapshot(
                service,
                instance,
                ProcessHandle.current().pid(),
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()),
                Instant.now(),
                versions,
                List.copyOf(fields));
    }
}
