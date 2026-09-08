package com.opsagent.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * Technical recovery requires distinct completed observations after the current handling result.
 *
 * @author heyu
 * @since 2026/9/3
 */
class EventRecoveryRulesTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-09-07T10:00:00Z");
    private final Instant resultAt = now.minusSeconds(600);
    private final EventRecoveryRules rules =
            new EventRecoveryRules(
                    "CORE=PROD,PROD=PROD", "ops-ticket-service,mysql", 180, 5, 30, 90);

    @Test
    void onlyExplicitEnvironmentAndAllowedTargetCanResolve() {
        assertThat(rules.environment("CORE", "ops-ticket-service")).isEqualTo("PROD");
        assertThatThrownBy(() -> rules.environment("DEMO", "ops-ticket-service"))
                .hasMessageContaining("环境");
        assertThatThrownBy(() -> rules.environment("CORE", "unregistered-service"))
                .hasMessageContaining("恢复目标");
    }

    @Test
    void completeNewEvidenceSpanningTimeAndCountPassesTechnicalBaseline() {
        assertThat(blocker(data())).isBlank();
    }

    @Test
    void fiveQuickSuccessesDoNotSubstituteForFullObservationWindow() {
        var data = data();
        var rows = (ArrayNode) data.path("inspections");
        rows.remove(6);
        rows.remove(5);
        assertThat(blocker(data)).contains("180秒");
    }

    @Test
    void successfulHistoryBeforeTheLatestHandlingResultCannotBeReused() {
        assertThat(rules.blocker(data(), "ops-ticket-service", "PROD", now.minusSeconds(20), now))
                .contains("本次处理结果之后");
    }

    @Test
    void freshResponseCannotRenewStaleCurrentMetric() {
        var data = data();
        ((ObjectNode) data.path("currentNode").path("metricEvidence").path("cpuUsage"))
                .put("sampledAt", now.minusSeconds(100).toString());
        assertThat(blocker(data)).contains("当前组件");
    }

    @Test
    void wrongIdentityOrUnavailableAlertsAreRejected() {
        var data = data();
        data.put("environment", "DEMO");
        assertThat(blocker(data)).contains("身份");
        data = data();
        data.put("alertsAvailable", false);
        assertThat(blocker(data)).contains("告警来源");
    }

    @Test
    void oldHistoryWithoutMetricIdentityCannotBeUpgradedByHealthyCurrentNode() {
        var data = data();
        ((ObjectNode) data.path("inspections").get(0).path("evidence")).remove("identity");
        assertThat(blocker(data)).contains("不完整巡检");
    }

    @Test
    void recentFailurePreventsOlderSuccessesFromClosingTheEvent() {
        var data = data();
        ((ObjectNode) data.path("inspections").get(1)).put("result", "ABNORMAL");
        assertThat(blocker(data)).contains("失败");
    }

    @Test
    void duplicateScrapeTimesDoNotIncreaseConsecutiveSuccesses() {
        var data = data();
        var rows = (ArrayNode) data.path("inspections");
        rows.removeAll();
        for (int index = 0; index < 8; index++) rows.add(row(index, now.minusSeconds(3)));
        assertThat(blocker(data)).contains("重复点击");
    }

    @Test
    void collectionGapResetsTheContinuousWindow() {
        var data = data();
        var rows = (ArrayNode) data.path("inspections");
        rows.removeAll();
        rows.add(row(0, now.minusSeconds(3)));
        rows.add(row(1, now.minusSeconds(130)));
        assertThat(blocker(data)).contains("断采");
    }

    @Test
    void manualDescriptionAndPartialObservationAreNotMachineEvidence() {
        var data = data();
        ((ObjectNode) data.path("currentNode").path("observation")).put("status", "PARTIAL");
        data.put("manualEvidence", "我已经确认成功");
        assertThat(blocker(data)).contains("技术恢复基线");
    }

    private String blocker(ObjectNode data) {
        return rules.blocker(data, "ops-ticket-service", "PROD", resultAt, now);
    }

    private ObjectNode data() {
        var data =
                json.createObjectNode()
                        .put("targetCode", "ops-ticket-service")
                        .put("environment", "PROD")
                        .put("generatedAt", now.toString())
                        .put("scope", "CURRENT")
                        .put("alertsAvailable", true);
        data.put("ticketId", 9);
        data.set("currentNode", node(now.minusSeconds(3)));
        var rows = data.putArray("inspections");
        for (int index = 0; index < 7; index++)
            rows.add(row(index, now.minusSeconds(3 + index * 30L)));
        return data;
    }

    private ObjectNode row(int index, Instant source) {
        var row =
                json.createObjectNode()
                        .put("ciCode", "ops-ticket-service")
                        .put("environment", "PROD")
                        .put("runId", "run-" + index)
                        .put("executionStatus", "COMPLETED")
                        .put("result", "PASS")
                        .put("lastCheckedAt", source.plusSeconds(1).toString());
        row.put("ticketId", 9);
        row.set("evidence", node(source));
        return row;
    }

    private ObjectNode node(Instant source) {
        var node =
                json.createObjectNode()
                        .put("ciCode", "ops-ticket-service")
                        .put("environment", "PROD")
                        .put("health", "HEALTHY")
                        .put("activeAlertCount", 0)
                        .put("observedAt", source.toString());
        node.putObject("identity").put("ciCode", "ops-ticket-service").put("environment", "PROD");
        node.putObject("observation").put("status", "READY");
        var evidence = node.putObject("metricEvidence");
        for (String key : List.of("cpuUsage", "memoryUsage"))
            evidence.putObject(key)
                    .put("value", 1)
                    .put("reasonCode", "OBSERVED")
                    .put("scope", "JVM_RUNTIME")
                    .put("sampledAt", source.toString());
        return node;
    }
}
