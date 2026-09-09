package com.opsagent.agent;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import java.util.Map;

/**
 * Persistent, specific authorization failures; never convert an unavailable identity service into
 * permission.
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AgentAccessFailure extends BusinessException {
    private static final Map<String, String> MESSAGES =
            Map.ofEntries(
                    Map.entry("VISITOR_REVOKED", "原访客已主动终止体验；需要管理员显式接管"),
                    Map.entry("VISITOR_LEASE_EXPIRED", "原访客体验身份已到期；需要管理员显式接管"),
                    Map.entry("ACTOR_DISABLED", "原执行身份已失效；需要管理员显式接管"),
                    Map.entry("ACTOR_ID_MISMATCH", "返回的执行身份与运行归属不一致"),
                    Map.entry("ACTOR_STATUS_UNAVAILABLE", "暂时无法核对执行身份，请稍后重新读取；未授予新的权限"),
                    Map.entry("ACTOR_ROLE_REVOKED", "原执行身份已不具备运行所需角色"),
                    Map.entry("ACTOR_LEASE_REQUIRED", "访客身份缺少有效体验租约"),
                    Map.entry("ACTOR_LEASE_EXPIRED", "执行身份授权期限已到，不能继续旧流程"),
                    Map.entry("ACTOR_INACTIVE", "原执行身份状态无效，无法授予操作权限"),
                    Map.entry("RUN_DEADLINE_EXPIRED", "本次运行已到期，不能继续旧流程"),
                    Map.entry("RUN_NOT_OWNED", "此运行不属于当前体验身份"),
                    Map.entry("ISOLATED_SCOPE_REQUIRED", "仅允许操作本人受支持的隔离演练"),
                    Map.entry("WORKFLOW_NOT_ALLOWED", "访客只能运行固定的隔离恢复流程"),
                    Map.entry("INCIDENT_CHANGED", "目标当前事件已变化，请重新核对现场"),
                    Map.entry("TARGET_REVISION_CHANGED", "目标配置版本已变化，请重新预览"),
                    Map.entry("TARGET_ALREADY_RECOVERED", "隔离目标已恢复，无需启动接管修复"),
                    Map.entry("RUN_BUSY", "旧运行仍在执行或存在未确认写入，请先核对执行结果"),
                    Map.entry("TAKEOVER_NOT_REQUIRED", "原执行身份仍有效，应由本人操作当前运行"),
                    Map.entry("TAKEOVER_ALREADY_CREATED", "此运行已有接管记录，请打开已创建的新运行"),
                    Map.entry("NEW_RUN_REQUIRED", "身份失效后的旧流程保留原结果，请使用显式接管的新运行"));
    private final String reasonCode;

    AgentAccessFailure(String reasonCode) {
        super(ErrorCode.FORBIDDEN, reasonCode + "：" + message(reasonCode));
        this.reasonCode = reasonCode;
    }

    String reasonCode() {
        return reasonCode;
    }

    static String message(String code) {
        return MESSAGES.getOrDefault(code, "当前执行身份无权执行此动作");
    }
}
