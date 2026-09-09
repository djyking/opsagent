package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * 验证实时运行事实、缺失数据与文档范围隔离，不向外部模型传配置正文或凭据。
 *
 * @author heyu
 * @since 2026/9/3
 */
class OperationsAnswerServiceTest {
    @Test
    void globalAnomalyPresetReadsLiveContextAndVisibleAlertsInsteadOfKnowledge() {
        var attention = mock(TicketAttentionClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                operations, "attention", attention);
        String now = Instant.now().toString();
        when(attention.activeAlerts("firing"))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        new TicketAttentionClient.Alert(
                                                "BusinessUnavailable",
                                                "ops-demo-order-service",
                                                "P2",
                                                "firing",
                                                2083L,
                                                "RESOLVED",
                                                now)),
                                "trace"));
        when(platform.operations())
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", partialSnapshot(), "trace"));
        var plan = operations.prepareIfApplicable("当前有哪些异常需要关注？", null, null, "openai");
        assertThat(plan).isNotNull();
        assertThat(plan.request().userPrompt())
                .contains("BusinessUnavailable", "2083", "RESOLVED", now);
        assertThat(plan.request().systemPrompt()).contains("旧告警不证明当前业务仍故障", "不要拿通用故障场景充数");
        assertThat(plan.sources()).hasSize(4);
        verify(attention).activeAlerts("firing");
        for (String question : List.of("现在有什么问题", "最近有哪些故障", "当前有哪些风险"))
            assertThat(operations.supports(question, null, null)).as(question).isTrue();
        assertThat(operations.supports("异常检测的原理是什么", null, null)).isFalse();
    }

    @Test
    void missingAlertSourceIsNotPresentedAsZeroAlerts() {
        var attention = mock(TicketAttentionClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                operations, "attention", attention);
        when(attention.activeAlerts("firing"))
                .thenThrow(new IllegalStateException("secret=not-visible"));
        when(platform.operations())
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", partialSnapshot(), "trace"));
        var plan = operations.prepareIfApplicable("当前有哪些异常需要关注？", null, null, "openai");
        assertThat(plan.fallback().answer())
                .contains("告警读取未成功", "不能将缺失列表当作零告警")
                .doesNotContain("not-visible");
    }

    private final PlatformClient platform = mock(PlatformClient.class);
    private final AiProperties ai = AiProviderSelectionTest.configured();
    private final OperationsAnswerService operations = new OperationsAnswerService(platform, ai);

    @Test
    void shouldGroundModelPromptInCurrentWhitelistedSourcesAndObservationTime() throws Exception {
        String now = Instant.now().toString();
        var snapshot =
                new ObjectMapper()
                        .readValue(
                                """
{"capturedAt":"%s","status":"ATTENTION","windowMinutes":30,
 "password":"NEVER_SEND_THIS","configContent":"UNRESTRICTED_CONFIGURATION",
 "targets":[{"service":"ops-rag-service","health":"UP","observedAt":"%s",
            "token":"NEVER_SEND_THIS"}],
 "metrics":[{"id":"heap","label":"堆内存","job":"rag","unit":"%%","currentValue":64.5,
              "forecastValue":70.0,"slopePerMinute":0.2,"sampleCount":30,
              "status":"RISK",
              "method":"LINEAR","observedAt":"%s"}],
 "nacos":{"status":"AVAILABLE","serviceCount":6,"healthyInstanceCount":6,
           "configurationCount":7,
           "config":"UNRESTRICTED_CONFIGURATION"},
 "sentinel":{"status":"AVAILABLE","ruleSource":"runtime","passedTotal":12,
             "blockedTotal":3,
             "metricsObservedAt":"%s","rules":[{"resource":"ops-rag-ask",
             "grade":"QPS","count":5}]}}
"""
                                        .formatted(now, now, now, now),
                                PlatformClient.OperationsContext.class);
        when(platform.operations())
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", snapshot, "trace"));
        var plan = operations.prepareIfApplicable("检查当前服务健康与内存趋势", null, null, "openai");
        assertThat(plan.provider()).isEqualTo("openai");
        assertThat(plan.request().userPrompt())
                .contains("64.500", "70.000", "LINEAR", now)
                .doesNotContain("NEVER_SEND_THIS", "UNRESTRICTED_CONFIGURATION");
        assertThat(plan.request().systemPrompt())
                .contains("空值/UNKNOWN/缺失数据不是0", "估计", "绝不声称已自动")
                .doesNotContain("NEVER_SEND_THIS");
        assertThat(plan.sources())
                .hasSize(3)
                .allSatisfy(
                        source -> {
                            assertThat(source.sourceType()).isEqualTo("OPERATIONS");
                            assertThat(source.sourceUrl()).isEqualTo("/operations");
                            assertThat(source.sourceUpdatedAt()).isEqualTo(now);
                            assertThat(source.sourceRetrievedAt()).isNotBlank();
                        });
    }

    @Test
    void shouldNeverInventLiveDataWhenSourceFailsOrIsStale() {
        when(platform.operations()).thenThrow(new IllegalStateException("token=DO_NOT_LEAK"));
        var failed = operations.prepareIfApplicable("当前Nacos状态", null, null, "openai");
        assertThat(failed.immediate().answer()).contains("不能确认").doesNotContain("DO_NOT_LEAK");
        assertThat(failed.immediate().references()).isEmpty();
        doReturn(
                        new KnowledgeClient.Envelope<>(
                                0, "ok", snapshot(Instant.now().minusSeconds(3600)), "test"))
                .when(platform)
                .operations();
        assertThat(
                        operations
                                .prepareIfApplicable("当前内存趋势", null, null, "openai")
                                .immediate()
                                .metadata()
                                .degradedReason())
                .isEqualTo("OPERATIONS_UNAVAILABLE");
    }

    @Test
    void shouldPreserveDocumentAndTicketScopesAndLeaveGeneralLearningToKnowledge() {
        for (String question : List.of("什么是 Prometheus 监控", "如何设计 Nacos 配置管理", "Sentinel 限流原理")) {
            assertThat(operations.prepareIfApplicable(question, null, null, "openai")).isNull();
        }
        assertThat(operations.prepareIfApplicable("当前内存趋势", 10L, null, "openai")).isNull();
        assertThat(operations.prepareIfApplicable("当前内存趋势", null, 20L, "openai")).isNull();
        verifyNoInteractions(platform);
    }

    @Test
    void shouldStateMissingMetricsAndKeepStructuredEvidenceWhenModelFails() {
        when(platform.operations())
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", partialSnapshot(), "test"));
        var plan = operations.prepareIfApplicable("当前Kubernetes节点磁盘是否正常", null, null, "openai");
        assertThat(plan.fallback().answer()).contains("未提供", "不能确认节点", "运行数据不可用");
        var invocation = mock(LlmInvocationService.class);
        when(invocation.stream(eq("openai"), anyString(), any(), any(), any()))
                .thenThrow(new AiProviderException("openai", 503, "unavailable", null));
        var props = new RagProperties();
        var metrics = new SimpleMeterRegistry();
        var rag =
                new RagService(
                        mock(KnowledgeClient.class),
                        props,
                        ai,
                        mock(PromptBuilder.class),
                        invocation,
                        new CitationValidator(),
                        mock(RerankService.class),
                        new ContextAssembler(props, metrics),
                        metrics,
                        mock(CmdbAnswerService.class),
                        operations);
        var result =
                rag.stream(plan, delta -> {}, new LlmInvocationService.AuditContext(7, "trace"));
        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.references()).hasSize(3);
        assertThat(result.answer()).contains("未切换其他模型", "未提供");
        assertThat(result.metadata().degradedReason()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(result.metadata().generationComplete()).isFalse();
        assertThat(result.metadata().finishReason()).isEqualTo("provider_unavailable");
        verify(invocation, never()).stream(anyString(), any(), any(), any());
    }

    @Test
    void shouldReturnMissingDataDirectlyWhenSnapshotContainsNoActualObservation() {
        when(platform.operations())
                .thenReturn(
                        new KnowledgeClient.Envelope<>(0, "ok", snapshot(Instant.now()), "test"));
        var plan = operations.prepareIfApplicable("当前服务健康与内存趋势", null, null, "openai");
        assertThat(plan.request()).isNull();
        assertThat(plan.immediate().provider()).isEqualTo("operations");
        assertThat(plan.immediate().answer()).contains("数据不可用", "未提供可用");
    }

    @Test
    void shouldSeparateFailedNacosReadAndHistoricalFitFromHealthAndForecastConfidence() {
        String now = Instant.now().toString();
        var snapshot =
                new PlatformClient.OperationsContext(
                        now,
                        "safe",
                        "UNKNOWN",
                        "",
                        30,
                        List.of(new PlatformClient.Target("ops-rag-service", "rag", "UP", now)),
                        List.of(
                                new PlatformClient.Metric(
                                        "5xx",
                                        "5xx 比例",
                                        "rag",
                                        "%",
                                        0.0,
                                        0.0,
                                        0.0,
                                        30,
                                        "STABLE",
                                        "最小二乘线性拟合，外推15分钟",
                                        "拟合R²=1.00",
                                        now)),
                        List.of(),
                        new PlatformClient.Nacos(
                                "UNAVAILABLE", "summary unavailable", null, null, null),
                        null);
        when(platform.operations())
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", snapshot, "test"));

        var plan = operations.prepareIfApplicable("当前 Nacos 状态与服务趋势预测", null, null, "openai");

        assertThat(plan.request().userPrompt())
                .contains("本次摘要读取状态：UNAVAILABLE", "注册服务数：未知", "实测 0.000", "拟合R²=1.00");
        assertThat(plan.request().systemPrompt())
                .contains("本次取数未成功", "不能据此认定组件不可达", "不是预测准确率、概率或置信度", "恒定零序列");
        assertThat(plan.fallback().answer())
                .contains("本次取数未成功", "不是预测准确率、概率或置信度", "不能证明未来继续为零或预测可信");
    }

    @Test
    void shouldPreserveMixedForecastCoverageAndSeparateSuccessfulReadsFromValidation()
            throws Exception {
        String now = Instant.now().toString();
        var snapshot =
                new ObjectMapper()
                        .readValue(
                                """
{"capturedAt":"%s","status":"HEALTHY","windowMinutes":60,
 "metrics":[{"id":"heap","label":"堆使用率","job":"rag","unit":"%%",
              "currentValue":15.65,"forecastValue":null,"sampleCount":61,
              "reason":"样本波动较大或存在采集空档","observedAt":"%s"},
            {"id":"5xx","label":"5xx比例","job":"rag","unit":"%%",
              "currentValue":0,"forecastValue":0,"sampleCount":61,
              "reason":"拟合R²=1.00","observedAt":"%s"}],
 "nacos":{"status":"AVAILABLE","serviceCount":6,"healthyInstanceCount":6,
           "configurationCount":7},
 "sentinel":{"status":"AVAILABLE","ruleSource":"Sentinel runtime FlowRuleManager",
             "passedTotal":0,"blockedTotal":0,"metricsObservedAt":"%s",
             "rules":[{"resource":"ops-rag-ask","grade":"QPS","count":5}]}}
"""
                                        .formatted(now, now, now, now),
                                PlatformClient.OperationsContext.class);
        when(platform.operations())
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", snapshot, "test"));

        var plan = operations.prepareIfApplicable("当前服务趋势和 Nacos 配置状态", null, null, "openai");

        assertThat(plan.request().userPrompt())
                .contains("可外推（已提供估计值） 1 项；不可外推（未提供估计值） 1 项")
                .contains("堆使用率 / rag：实测 15.650 %；15分钟外推：无（未提供估计值）")
                .contains("5xx比例 / rag：实测 0.000 %；15分钟外推：有（估计值 0.000）")
                .contains("样本波动较大或存在采集空档", "不能仅由空值推定 R² 低于阈值")
                .contains("配置条目数：7", "不代表配置正确性或配置功能验证")
                .contains("读取状态：AVAILABLE\n规则来源：Sentinel runtime FlowRuleManager");
        assertThat(plan.fallback().answer())
                .contains("可外推（已提供估计值） 1 项；不可外推（未提供估计值） 1 项", "不代表配置正确性或配置功能验证");
    }

    private PlatformClient.OperationsContext partialSnapshot() {
        String now = Instant.now().toString();
        return new PlatformClient.OperationsContext(
                now,
                "safe",
                "UNKNOWN",
                "",
                30,
                List.of(new PlatformClient.Target("ops-rag-service", "rag", "UP", now)),
                List.of(),
                List.of(),
                null,
                null);
    }

    private PlatformClient.OperationsContext snapshot(Instant captured) {
        return new PlatformClient.OperationsContext(
                captured.toString(),
                "safe",
                "UNKNOWN",
                "",
                30,
                List.of(),
                List.of(),
                List.of(),
                null,
                null);
    }
}
