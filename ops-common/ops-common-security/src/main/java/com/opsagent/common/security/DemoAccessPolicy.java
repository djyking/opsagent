package com.opsagent.common.security;

/**
 * Explicit allowlist for public visitors, enforced inside every business service.
 *
 * @author heyu
 * @since 2026/9/3
 */
public final class DemoAccessPolicy {
    private DemoAccessPolicy() {}

    public static boolean allows(String method, String path) {
        if ("GET".equals(method)) {
            return path.equals("/api/auth/me")
                    || observabilityRead(path)
                    || path.equals("/api/platform/configuration/managed")
                    || path.matches(
                            "/api/platform/configuration/managed/order-(?:business|runtime)(?:/history)?")
                    || path.equals("/api/automation/approvals/pending")
                    || path.equals("/api/automation/summary")
                    || path.matches("/api/automation/tickets/[0-9]+/workspace")
                    || path.matches("/api/automation/(?:models|tools|definitions|runs)")
                    || path.matches("/api/automation/definitions/[A-Za-z0-9_-]+")
                    || path.matches("/api/automation/runs/[A-Za-z0-9_-]+(?:/(?:events|stream))?")
                    || path.matches("/api/platform/operations/demo/(?:scenarios|target)")
                    || path.equals("/api/tickets")
                    || path.equals("/api/tickets/alerts")
                    || path.matches(
                            "/api/tickets/[0-9]+(?:/(?:history|comments|work-records|sla))?")
                    || path.startsWith("/api/tickets/sla/")
                    || path.equals("/api/platform/monitor/summary")
                    || path.equals("/api/platform/cmdb/cis")
                    || path.equals("/api/platform/cmdb/relations")
                    || path.matches("/api/platform/cmdb/cis/[^/]+/topology")
                    || path.startsWith("/api/platform/oncall/")
                    || path.matches(
                            "/api/platform/operations/(?:overview|context|workflows|runs(?:/[0-9]+)?)")
                    || path.equals("/api/rag/providers")
                    || path.equals("/api/rag/runtime/sentinel")
                    || path.matches("/api/rag/conversations(?:/[a-f0-9-]+(?:/messages)?)?")
                    || path.equals("/api/knowledge/bases")
                    || path.equals("/api/knowledge/internal/search")
                    || path.matches("/api/knowledge/bases/[0-9]+/documents")
                    || path.matches(
                            "/api/knowledge/(?:documents/[0-9]+(?:/chunks)?|tickets/[0-9]+/documents)");
        }
        if ("POST".equals(method)) {
            return path.equals("/api/auth/logout")
                    || path.equals("/api/platform/observability/evidence")
                    || path.equals("/api/platform/observability/evidence/resolve")
                    || path.matches(
                            "/api/platform/observability/inspections/[A-Za-z0-9_-]+/run")
                    || path.equals("/api/automation/runs")
                    || path.matches(
                            "/api/automation/runs/[A-Za-z0-9_-]+/(?:cancel|pause|resume|input)")
                    || path.matches("/api/automation/approvals/[A-Za-z0-9_-]+/decision")
                    || path.equals("/api/platform/operations/demo/scenarios")
                    || path.equals("/api/platform/operations/demo/actions")
                    || path.matches(
                            "/api/tickets/[0-9]+/(?:claim|transition|comments|work-records)")
                    || path.matches("/api/rag/(?:chat|ask|stream)")
                    || path.equals("/api/rag/conversations")
                    || path.matches("/api/rag/conversations/[a-f0-9-]+/stream");
        }
        return ("PATCH".equals(method) || "PUT".equals(method) || "DELETE".equals(method))
                && path.matches("/api/rag/conversations/[a-f0-9-]+");
    }

    private static boolean observabilityRead(String path) {
        return path.matches("/api/platform/observability/v3/(?:topology|traces|history|differences)")
                || path.matches("/api/platform/observability/v3/services/[A-Za-z0-9_-]+/instances")
                || path.matches("/api/platform/observability/v3/traces/[a-f0-9]{32}")
                || path.matches("/api/platform/observability/v3/history/[a-f0-9-]{36}")
                || path.matches("/api/platform/observability/(?:topology|wallboard|inspections)")
                || path.matches("/api/platform/observability/services/[A-Za-z0-9_-]+")
                || path.matches("/api/platform/observability/inspections/[A-Za-z0-9_-]+/history")
                || path.equals("/api/platform/config-center")
                || path.matches("/api/platform/config-center/[A-Za-z0-9_-]+(?:/(?:history|diff))?")
                || path.matches("/api/platform/traffic(?:/(?:summary|history))?")
                || path.matches("/api/platform/traffic/rules/[A-Za-z_]+")
                || path.equals("/api/rag/runtime/traffic");
    }
}
