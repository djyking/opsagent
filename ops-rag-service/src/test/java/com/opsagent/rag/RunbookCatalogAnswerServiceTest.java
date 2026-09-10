package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * 实际目录、发布版本、服务适用性与执行权限的定向校验，不创建运行。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RunbookCatalogAnswerServiceTest {
    private final AutomationCatalogClient client = mock(AutomationCatalogClient.class);
    private final RunbookCatalogAnswerService service = new RunbookCatalogAnswerService(client);

    @BeforeEach
    void setup() {
        identity("DEMO");
        when(client.definitions())
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        new AutomationCatalogClient.Definition(
                                                "isolated-recovery", "隔离业务故障诊断与恢复", 7),
                                        new AutomationCatalogClient.Definition(
                                                "configuration-change", "受控配置变更与应用核验", 3)),
                                "test"));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void gatewayHasRealCatalogLinksButNoInventedExecutableRecoveryMapping() {
        var answer =
                service.answer(
                        "推荐相关 Runbook",
                        new ObservabilityContext("ops-gateway", "PROD", "15m", null));
        assertThat(answer.answer())
                .contains(
                        "ops-gateway",
                        "未配置可直接执行",
                        "隔离业务故障诊断与恢复",
                        "v7",
                        "/automation?tab=workflows&definition=isolated-recovery",
                        "本次没有创建运行或执行操作",
                        "生产 API 网关不在执行范围",
                        "访客只可发起及审批本人隔离演练");
        assertThat(answer.answer()).doesNotContain("[打开受控配置中心]");
        assertThat(answer.metadata().retrievalMode()).isEqualTo("RUNBOOK_CATALOG");
        assertThat(answer.inputTokens()).isZero();
        assertThat(answer.metadata().requestAttempts()).isZero();
        verify(client).definitions();
    }

    @Test
    void isolatedNotificationTargetLinksToItsOwnExperienceAndRequiresConfirmation() {
        var answer =
                service.answer(
                        "推荐相关 Runbook",
                        new ObservabilityContext(
                                "ops-demo-notification-service", "DEMO", "15m", null));
        assertThat(answer.answer())
                .contains(
                        "属于受控隔离目标",
                        "RabbitMQ 消费暂停",
                        "/automation?tab=experience&target=ops-demo-notification-service",
                        "链接本身不发起演练");
    }

    @Test
    void adminGetsConfigurationEntryWhileNormalReaderGetsOnlyReadLinks() {
        identity("ADMIN");
        assertThat(service.answer("Runbook目录有哪些", null).answer()).contains("[打开受控配置中心]");
        identity("USER");
        assertThat(service.answer("Runbook目录有哪些", null).answer())
                .doesNotContain("[打开受控配置中心]", "[打开我的隔离演练]")
                .contains("[查看具体流程]", "普通只读账号可查看目录");
    }

    @Test
    void unpublishedOrInvalidDirectoryRowsDoNotBecomeExecutableRecommendations() {
        when(client.definitions())
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        new AutomationCatalogClient.Definition(
                                                "isolated-recovery", "未发布草稿", 0),
                                        new AutomationCatalogClient.Definition(
                                                "javascript:bad", "不可信名称", 1)),
                                "test"));
        var answer =
                service.answer(
                        "推荐Runbook",
                        new ObservabilityContext("ops-demo-order-service", "DEMO", "15m", null));
        assertThat(answer.answer())
                .contains("当前没有已发布的工作流")
                .doesNotContain("未发布草稿", "不可信名称", "可使用隔离故障诊断", "[打开我的隔离演练]");
    }

    @Test
    void failedReadDoesNotInventCatalogAndPermissionFailureStaysDenied() {
        when(client.definitions()).thenThrow(new IllegalStateException("private credentials"));
        var answer = service.answer("推荐Runbook", null);
        assertThat(answer.answer()).contains("目录暂时无法读取").doesNotContain("private", "隔离业务故障诊断与恢复");
        assertThat(answer.metadata().degraded()).isTrue();
        doReturn(new KnowledgeClient.Envelope<>(40300, "denied", null, "test"))
                .when(client)
                .definitions();
        assertThatThrownBy(() -> service.answer("推荐Runbook", null))
                .isInstanceOf(BusinessException.class);
    }

    private void identity(String role) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(-7, "visitor", "test", List.of(role)),
                                null,
                                List.of()));
    }
}
