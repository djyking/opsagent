package com.opsagent.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通知恢复必须同时完成实际投递和队列消退，TTL、精确版本与来源不可绕过。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DemoNotificationRuntimeTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final DemoNotificationBroker broker = mock(DemoNotificationBroker.class);
    private final DemoNotificationRuntime runtime = new DemoNotificationRuntime(broker, json);

    @Test
    void configPauseAffectsOnlyNotificationConsumerAndTtlRestoresWithoutAgent() throws Exception {
        var fault =
                DemoNotificationConfiguration.fault(
                        UUID.randomUUID().toString(),
                        DemoNotificationConfiguration.SCENARIO,
                        Instant.now().plusSeconds(600));
        runtime.accept(json.writeValueAsString(fault));
        assertThat(runtime.snapshot().get("previousConfiguration")).isEqualTo(Map.of());
        verify(broker).maintain(false);
        var expired =
                new DemoNotificationConfiguration(
                        fault.incidentId(),
                        fault.scenarioCode(),
                        fault.revision(),
                        Instant.now().minusSeconds(1).getEpochSecond(),
                        false,
                        "");
        ReflectionTestUtils.setField(runtime, "current", expired);
        runtime.guard();
        assertThat(runtime.snapshot())
                .containsEntry("status", "BASELINE")
                .containsEntry("recoverySource", "TTL_GUARD");
        verify(broker).maintain(true);
    }

    @Test
    void anAcknowledgedProbeDoesNotMeanRecoveryWhileBacklogOrConsumerFailureRemain()
            throws Exception {
        when(broker.probe())
                .thenReturn(
                        new DemoNotificationBroker.Delivery(
                                true,
                                UUID.randomUUID().toString(),
                                Instant.now(),
                                "NOTIFICATION_DELIVERED"));
        when(broker.snapshot()).thenReturn(Map.of("messagesReady", 4, "consumerCount", 1));
        assertThat(runtime.preview())
                .containsEntry("httpStatus", 503)
                .containsEntry("queueDrained", false);
        when(broker.snapshot()).thenReturn(Map.of("messagesReady", 0, "consumerCount", 0));
        assertThat(runtime.preview()).containsEntry("httpStatus", 503);
        when(broker.snapshot()).thenReturn(Map.of("messagesReady", 0, "consumerCount", 1));
        assertThat(runtime.preview())
                .containsEntry("httpStatus", 200)
                .containsEntry("queueDrained", true);
    }

    @Test
    void restoreRequiresIncidentRevisionAndExactActionAndPreservesManualSource() throws Exception {
        var fault =
                DemoNotificationConfiguration.fault(
                        UUID.randomUUID().toString(),
                        DemoNotificationConfiguration.SCENARIO,
                        Instant.now().plusSeconds(600));
        runtime.accept(json.writeValueAsString(fault));
        assertThatThrownBy(
                        () ->
                                runtime.restore(
                                        fault.incidentId(),
                                        "RESTORE_CONFIGURATION",
                                        fault.revision(),
                                        "MANUAL"))
                .hasMessage("ACTION_SCENARIO_MISMATCH");
        assertThatThrownBy(
                        () ->
                                runtime.restore(
                                        fault.incidentId(),
                                        "RESTORE_QUEUE_CONSUMER",
                                        "a".repeat(64),
                                        "MANUAL"))
                .hasMessage("REVISION_CONFLICT");
        AtomicReference<String> content = new AtomicReference<>(json.writeValueAsString(fault));
        ConfigService config = mock(ConfigService.class);
        when(config.publishConfig(
                        eq(DemoNotificationRuntime.DATA_ID),
                        eq("OPSAGENT_DEMO"),
                        anyString(),
                        eq("json")))
                .thenAnswer(
                        call -> {
                            content.set(call.getArgument(2));
                            return true;
                        });
        when(config.getConfig(eq(DemoNotificationRuntime.DATA_ID), eq("OPSAGENT_DEMO"), anyLong()))
                .thenAnswer(call -> content.get());
        ReflectionTestUtils.setField(runtime, "config", config);
        var result =
                runtime.restore(
                        fault.incidentId(), "RESTORE_QUEUE_CONSUMER", fault.revision(), "MANUAL");
        assertThat(result)
                .containsEntry("recoverySource", "MANUAL")
                .containsEntry("consumerEnabled", true)
                .containsEntry("configurationStatus", "APPLIED");
    }

    @Test
    void persistedPauseCannotClaimAgentRecoveryOrCarryUnboundedTtl() {
        var invalid =
                new DemoNotificationConfiguration(
                        UUID.randomUUID().toString(),
                        DemoNotificationConfiguration.SCENARIO,
                        "a".repeat(64),
                        Instant.now().plusSeconds(600).getEpochSecond(),
                        false,
                        "AGENT_TOOL");
        assertThat(invalid.valid()).isFalse();
        assertThatThrownBy(
                        () ->
                                DemoNotificationConfiguration.fault(
                                        UUID.randomUUID().toString(),
                                        DemoNotificationConfiguration.SCENARIO,
                                        Instant.now().plusSeconds(10000)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
