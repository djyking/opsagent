package com.opsagent.agent;

import com.opsagent.common.security.InternalActorTokens.Context;

import java.util.Map;

/**
 * 固定演练目标路由；模型不能提供服务地址或跨目标执行修复。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class AgentTargets {
    static final String ORDER = "ops-demo-order-service";
    static final String NOTIFICATION = "ops-demo-notification-service";
    private static final Map<String, String> PATHS =
            Map.of(ORDER, "order", NOTIFICATION, "notification");

    private AgentTargets() {}

    static boolean supported(String target) {
        return PATHS.containsKey(target);
    }

    static String path(String target) {
        String path = PATHS.get(target);
        if (path == null) throw AgentClients.denied();
        return "/internal/platform/demo-targets/" + path;
    }

    static Context bind(Context actor, String target) {
        if (!supported(target)) throw AgentClients.denied();
        return new Context(
                actor.userId(),
                actor.username(),
                actor.roles(),
                actor.runId(),
                target,
                actor.validUntil());
    }

    static boolean allows(String target, String tool) {
        if (!supported(target)) return false;
        return switch (tool) {
            case "demo_queue_restore" -> NOTIFICATION.equals(target);
            case "demo_config_restore", "demo_flow_restore" -> ORDER.equals(target);
            default -> true;
        };
    }
}
