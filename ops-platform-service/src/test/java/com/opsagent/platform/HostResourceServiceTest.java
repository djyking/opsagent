package com.opsagent.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class HostResourceServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void linuxHostRequiresRegisteredJobAndScope() throws Exception {
        Instant now = Instant.parse("2026-09-07T15:00:00Z");
        long t = now.getEpochSecond();
        var matrix = json.createArrayNode();
        add(matrix, "opsagent_infra_check_timestamp_seconds", "cloud-host", t, t - 5);
        add(matrix, "opsagent_infra_read_success", "cloud-host", t, 1);
        add(matrix, "opsagent_infra_host_cpu_usage_percent", "cloud-host", t, 17);
        for (var row : matrix)
            ((com.fasterxml.jackson.databind.node.ObjectNode) row.path("metric"))
                    .put("job", HostResourceService.LINUX_JOB)
                    .put("scope", "LINUX_HOST");
        var binding =
                json.readTree(
                        "{\"prometheusJob\":\"opsagent-linux-host\",\"hostDisks\":\"/\",\"hostInterfaces\":\"eth0\"}");
        var metrics =
                HostResourceService.parse(
                        matrix, "cloud-host", "CORE", binding, now.minusSeconds(900), now);
        assertEquals(
                17D,
                metrics.stream()
                        .filter(m -> m.key().equals("cpuUsage"))
                        .findFirst()
                        .orElseThrow()
                        .value());
        ((com.fasterxml.jackson.databind.node.ObjectNode) matrix.get(2).path("metric"))
                .put("scope", "WINDOWS_HOST");
        metrics =
                HostResourceService.parse(
                        matrix, "cloud-host", "CORE", binding, now.minusSeconds(900), now);
        assertNull(
                metrics.stream()
                        .filter(m -> m.key().equals("cpuUsage"))
                        .findFirst()
                        .orElseThrow()
                        .value());
    }

    @Test
    void keepsOriginalTimeAndTrueZeroRejectsAnotherHostAndStaleCurrent() throws Exception {
        Instant now = Instant.parse("2026-09-07T15:00:00Z");
        long t = now.getEpochSecond();
        var matrix = json.createArrayNode();
        add(matrix, "opsagent_infra_check_timestamp_seconds", "host", t, t - 10);
        add(matrix, "opsagent_infra_read_success", "host", t, 1);
        add(matrix, "opsagent_infra_host_cpu_usage_percent", "host", t, 0);
        add(matrix, "opsagent_infra_host_physical_memory_usage_percent", "other", t, 70);
        var binding = json.readTree("{\"hostDisks\":\"C:\",\"hostInterfaces\":\"WLAN\"}");
        var metrics =
                HostResourceService.parse(
                        matrix, "host", "CORE", binding, now.minusSeconds(900), now);
        var cpu =
                metrics.stream().filter(m -> m.key().equals("cpuUsage")).findFirst().orElseThrow();
        assertEquals(0D, cpu.value());
        assertEquals(now.minusSeconds(10), cpu.sampledAt());
        assertEquals("OBSERVED", cpu.status());
        assertNull(
                metrics.stream()
                        .filter(m -> m.key().equals("physicalMemoryUsage"))
                        .findFirst()
                        .orElseThrow()
                        .value());
        var stale =
                HostResourceService.parse(
                                matrix,
                                "host",
                                "CORE",
                                binding,
                                now.minusSeconds(900),
                                now.plusSeconds(200))
                        .stream()
                        .filter(m -> m.key().equals("cpuUsage"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("STALE", stale.status());
        assertNull(stale.value());
        assertEquals(1, stale.points().size());
    }

    @Test
    void latestFailedCheckCannotReuseRecentSuccessAsCurrent() throws Exception {
        Instant now = Instant.parse("2026-09-07T15:00:00Z");
        long t = now.getEpochSecond();
        var matrix = json.createArrayNode();
        var checks = add(matrix, "opsagent_infra_check_timestamp_seconds", "host", t - 20, t - 25);
        checks.withArray("values").addArray().add(t).add(Double.toString(t - 5));
        var success = add(matrix, "opsagent_infra_read_success", "host", t - 20, 1);
        success.withArray("values").addArray().add(t).add("0");
        add(matrix, "opsagent_infra_host_cpu_usage_percent", "host", t - 20, 25);
        var binding = json.readTree("{\"hostDisks\":\"C:\",\"hostInterfaces\":\"WLAN\"}");

        var metrics =
                HostResourceService.parse(
                        matrix, "host", "CORE", binding, now.minusSeconds(900), now);
        var cpu =
                metrics.stream().filter(m -> m.key().equals("cpuUsage")).findFirst().orElseThrow();
        assertNull(cpu.value(), "a recent success is history after a newer check failed");
        assertEquals("MISSING", cpu.status());
        assertEquals(now.minusSeconds(25), cpu.sampledAt());
        assertEquals(1, cpu.points().size());
        assertEquals(25D, cpu.points().get(0).value());
        assertTrue(metrics.stream().noneMatch(m -> "OBSERVED".equals(m.status())));
    }

    @Test
    void missingDiskInLatestPartialCheckKeepsHistoryAndOtherCurrentMetrics() throws Exception {
        Instant now = Instant.parse("2026-09-07T15:00:00Z");
        long t = now.getEpochSecond();
        var matrix = json.createArrayNode();
        var checks = add(matrix, "opsagent_infra_check_timestamp_seconds", "host", t - 20, t - 25);
        checks.withArray("values").addArray().add(t).add(Double.toString(t - 5));
        var success = add(matrix, "opsagent_infra_read_success", "host", t - 20, 1);
        success.withArray("values").addArray().add(t).add("1");
        var cpuRow = add(matrix, "opsagent_infra_host_cpu_usage_percent", "host", t - 20, 25);
        cpuRow.withArray("values").addArray().add(t).add("0");
        var diskRow = add(matrix, "opsagent_infra_host_disk_usage_percent", "host", t - 20, 80);
        diskRow.withObject("metric").put("disk", "C:");
        var binding = json.readTree("{\"hostDisks\":\"C:\",\"hostInterfaces\":\"WLAN\"}");

        var metrics =
                HostResourceService.parse(
                        matrix, "host", "CORE", binding, now.minusSeconds(900), now);
        var cpu =
                metrics.stream().filter(m -> m.key().equals("cpuUsage")).findFirst().orElseThrow();
        assertEquals("OBSERVED", cpu.status());
        assertEquals(0D, cpu.value());
        assertEquals(now.minusSeconds(5), cpu.sampledAt());
        var disk =
                metrics.stream()
                        .filter(m -> m.key().equals("diskUsage") && m.dimension().equals("C:"))
                        .findFirst()
                        .orElseThrow();
        assertNull(disk.value(), "a missing disk must not inherit its previous successful sample");
        assertEquals("MISSING", disk.status());
        assertEquals(1, disk.points().size());
        assertEquals(80D, disk.points().get(0).value());
    }

    private com.fasterxml.jackson.databind.node.ObjectNode add(
            com.fasterxml.jackson.databind.node.ArrayNode matrix,
            String name,
            String ci,
            long at,
            double value) {
        var row = matrix.addObject();
        row.putObject("metric")
                .put("__name__", name)
                .put("job", HostResourceService.JOB)
                .put("ci_code", ci)
                .put("host_id", ci)
                .put("environment", "CORE")
                .put("scope", "WINDOWS_HOST");
        row.putArray("values").addArray().add(at).add(Double.toString(value));
        return row;
    }
}
