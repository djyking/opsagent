package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 推荐实际已发布流程及适用边界；目录推荐不会创建运行、执行工具或审批。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class RunbookCatalogAnswerService {
    private static final Pattern REQUEST =
            Pattern.compile(
                    "(?i)(?:runbook|处置手册).*(?:推荐|目录|有哪些|在哪|入口|执行|运行|启动)|(?:推荐|查找|列出|有哪些|执行|运行|启动).*(?:runbook|处置手册)");
    private static final Set<String> ISOLATED =
            Set.of("ops-demo-order-service", "ops-demo-notification-service");
    private final AutomationCatalogClient catalog;

    RunbookCatalogAnswerService(AutomationCatalogClient catalog) {
        this.catalog = catalog;
    }

    static boolean supports(String question) {
        return REQUEST.matcher(AssistantIntent.body(question)).find();
    }

    RagService.Answer answer(String question, ObservabilityContext context) {
        String service = context == null ? "" : context.service();
        StringBuilder text = new StringBuilder("以下来自系统实际工作流目录，仅提供推荐和入口，本次没有创建运行或执行操作。\n\n");
        List<AutomationCatalogClient.Definition> definitions;
        try {
            var response = catalog.definitions();
            if (response == null || response.code() != 0 || response.data() == null) {
                if (response != null && (response.code() == 40100 || response.code() == 40300))
                    throw new BusinessException(ErrorCode.FORBIDDEN, "无权读取工作流目录");
                return unavailable();
            }
            definitions =
                    response.data().stream()
                            .filter(
                                    item ->
                                            item.id() != null
                                                    && item.id().matches("[a-z][a-z0-9-]{0,60}"))
                            .filter(item -> item.published_version() > 0)
                            .limit(20)
                            .toList();
        } catch (BusinessException denied) {
            throw denied;
        } catch (feign.FeignException failure) {
            if (failure.status() == 401 || failure.status() == 403)
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权读取工作流目录");
            return unavailable();
        } catch (RuntimeException failure) {
            return unavailable();
        }
        boolean isolated = ISOLATED.contains(service);
        if (service.isBlank()) text.append("尚未指定服务：以下按适用范围列出已发布流程，选择服务后可核对关联。\n\n");
        else if (isolated
                && definitions.stream().anyMatch(item -> item.id().equals("isolated-recovery")))
            text.append("当前服务 **")
                    .append(service)
                    .append("** 属于受控隔离目标，可使用隔离故障诊断与恢复流程；实际启动仍需本人事件、可用模型和前置检查。\n\n");
        else
            text.append("当前服务 **")
                    .append(service)
                    .append("** 未配置可直接执行的自动恢复 Runbook。以下流程不能据此套用为该生产服务的自动恢复方案。\n\n");
        if (definitions.isEmpty()) text.append("当前没有已发布的工作流。\n\n");
        for (var definition : definitions) {
            text.append("- **")
                    .append(label(definition.name()))
                    .append("** · v")
                    .append(definition.published_version())
                    .append("：");
            switch (definition.id()) {
                case "isolated-recovery" ->
                        text.append("适用于隔离订单服务的 Redis 配置漂移、Sentinel 规则回退，以及隔离通知服务的 RabbitMQ 消费暂停。")
                                .append("生产 API 网关不在执行范围。 ");
                case "configuration-change" ->
                        text.append("适用于配置中心已登记的受控配置变更、发布与应用核验；仅管理员可发起，不是通用重启或自动修复流程。 ");
                default -> text.append("目录尚未登记此流程与当前服务的适用映射，请先核对定义和前置条件。 ");
            }
            text.append("[查看具体流程](/automation?tab=workflows&definition=")
                    .append(definition.id())
                    .append(")\n");
        }
        boolean recovery =
                definitions.stream().anyMatch(item -> item.id().equals("isolated-recovery"));
        boolean configuration =
                definitions.stream().anyMatch(item -> item.id().equals("configuration-change"));
        var roles = SecurityUsers.current().roles();
        if (recovery
                && (roles.contains("DEMO") || roles.contains("OPS") || roles.contains("ADMIN"))) {
            text.append("\n[打开我的隔离演练](/automation?tab=experience&target=")
                    .append(isolated ? service : "ops-demo-order-service")
                    .append(")：进入后选择场景并核对确认，链接本身不发起演练。\n");
        }
        if (configuration && roles.contains("ADMIN"))
            text.append("\n[打开受控配置中心](/observability/config/managed)：选择已登记配置后按变更流程操作。\n");
        text.append(
                        "\n"
                            + "权限：访客只可发起及审批本人隔离演练，不能操作生产配置或他人任务；普通只读账号可查看目录，管理员/运维仍受事件归属和流程前置校验。配置发布及工作流定义发布由管理员管理。")
                .append("\n\n知识库里的手册是诊断参考，不因被引用而成为可执行流程。实际执行结果请在[运行与审批](/automation?tab=runs)查看。")
                .append("\n\n目录来源：自动化中心 · 读取时间：")
                .append(Instant.now())
                .append("。");
        return result(text.toString(), false);
    }

    private RagService.Answer unavailable() {
        return result(
                "工作流目录暂时无法读取，无法确认现有 Runbook"
                    + " 名称、版本和适用服务。请稍后重试，或打开[自动化中心](/automation)核对。本次没有创建运行或执行操作。",
                true);
    }

    private RagService.Answer result(String text, boolean unavailable) {
        return new RagService.Answer(
                text,
                List.of(),
                "system",
                "capability-catalog",
                0,
                0,
                0,
                new RagService.AnswerMetadata(
                        "RUNBOOK_CATALOG",
                        false,
                        0,
                        0,
                        0,
                        unavailable,
                        unavailable ? "AUTOMATION_CATALOG_UNAVAILABLE" : null,
                        true,
                        "structured_data",
                        0,
                        0,
                        0,
                        true,
                        0));
    }

    private String label(String name) {
        return ObservabilityEvidenceClient.safeText(name, 120)
                .replaceAll("[\\r\\n\\[\\]*|<>]", " ");
    }
}
