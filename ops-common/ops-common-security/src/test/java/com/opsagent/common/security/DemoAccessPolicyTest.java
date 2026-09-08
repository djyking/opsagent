package com.opsagent.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Visitor writes are limited to their own server-authorized AI conversations.
 *
 * @author heyu
 * @since 2026/9/3
 */
class DemoAccessPolicyTest {
    @Test
    void blocksBusinessMutationsAndAdministrativeReads() {
        for (String path :
                new String[] {
                    "/api/tickets",
                    "/api/automation/agents",
                    "/api/platform/operations/runs",
                    "/api/knowledge/bases/1/documents",
                    "/api/knowledge/admin/reindex",
                    "/api/platform/cmdb/cis"
                }) {
            assertThat(DemoAccessPolicy.allows("POST", path)).as(path).isFalse();
        }
        for (String path :
                new String[] {
                    "/api/platform/admin/configuration",
                    "/api/rag/admin/providers",
                    "/api/knowledge/admin/index/consistency",
                    "/api/tickets/1/trace",
                    "/new-api"
                }) {
            assertThat(DemoAccessPolicy.allows("GET", path)).as(path).isFalse();
        }
    }

    @Test
    void permitsOnlyExplicitDrillRoutesWhoseServicesEnforceOwnership() {
        assertThat(DemoAccessPolicy.allows("POST", "/api/tickets/1/claim")).isTrue();
        assertThat(DemoAccessPolicy.allows("POST", "/api/automation/runs")).isTrue();
        assertThat(DemoAccessPolicy.allows("POST", "/api/automation/runs/abc-123/cancel")).isTrue();
        assertThat(DemoAccessPolicy.allows("POST", "/api/automation/runs/abc-123/pause")).isTrue();
        assertThat(DemoAccessPolicy.allows("POST", "/api/automation/runs/abc-123/resume")).isTrue();
        assertThat(DemoAccessPolicy.allows("POST", "/api/automation/approvals/abc-123/decision"))
                .isTrue();
        assertThat(DemoAccessPolicy.allows("PUT", "/api/automation/agents/1")).isFalse();
        assertThat(DemoAccessPolicy.allows("GET", "/api/automation/models")).isTrue();
        assertThat(DemoAccessPolicy.allows("GET", "/api/automation/definitions/1")).isTrue();
        assertThat(DemoAccessPolicy.allows("GET", "/api/automation/runs/abc-123/stream")).isTrue();
        assertThat(DemoAccessPolicy.allows("GET", "/api/automation/runs/abc-123/usage")).isTrue();
        assertThat(DemoAccessPolicy.allows("POST", "/api/automation/runs/abc-123/usage")).isFalse();
        assertThat(DemoAccessPolicy.allows("POST", "/api/automation/models")).isFalse();
        assertThat(DemoAccessPolicy.allows("POST", "/api/automation/definitions/validate"))
                .isFalse();
        assertThat(DemoAccessPolicy.allows("GET", "/api/tickets/alerts")).isTrue();
        assertThat(DemoAccessPolicy.allows("POST", "/api/platform/operations/demo/actions"))
                .isTrue();
        assertThat(DemoAccessPolicy.allows("DELETE", "/api/tickets/1")).isFalse();
        assertThat(DemoAccessPolicy.allows("POST", "/api/automation/runs/abc-123/execute-shell"))
                .isFalse();
    }

    @Test
    void permitsReadOnlyOperationsAndPrivateChatActions() {
        assertThat(DemoAccessPolicy.allows("GET", "/api/platform/operations/context")).isTrue();
        assertThat(DemoAccessPolicy.allows("GET", "/api/tickets/12/history")).isTrue();
        assertThat(DemoAccessPolicy.allows("POST", "/api/rag/conversations")).isTrue();
        assertThat(DemoAccessPolicy.allows("POST", "/api/rag/conversations/abc-123/stream"))
                .isTrue();
        assertThat(DemoAccessPolicy.allows("DELETE", "/api/rag/conversations/abc-123")).isTrue();
        assertThat(DemoAccessPolicy.allows("GET", "/api/knowledge/internal/search")).isTrue();
    }

    @Test
    void doesNotAllowSubpathsOrPrefixCollisions() {
        assertThat(DemoAccessPolicy.allows("GET", "/api/platform/operations/runs/1/execute"))
                .isFalse();
        assertThat(DemoAccessPolicy.allows("POST", "/api/rag/stream-extra")).isFalse();
        assertThat(DemoAccessPolicy.allows("DELETE", "/api/rag/conversations/abc/anything"))
                .isFalse();
    }

    @Test
    void permitsObservabilityReadsAndReadOnlyChecksWithoutGovernanceWrites() {
        for (String path :
                new String[] {
                    "/api/platform/observability/topology",
                    "/api/platform/observability/wallboard",
                    "/api/platform/observability/services/ops-rag-service",
                    "/api/platform/observability/inspections",
                    "/api/platform/observability/inspections/ops-rag-service/history",
                    "/api/platform/config-center",
                    "/api/platform/config-center/summary",
                    "/api/platform/config-center/nacos_YQ/history",
                    "/api/platform/config-center/nacos_YQ/diff",
                    "/api/platform/traffic",
                    "/api/platform/traffic/summary",
                    "/api/platform/traffic/rules/FLOW",
                    "/api/platform/traffic/history",
                    "/api/rag/runtime/traffic"
                }) assertThat(DemoAccessPolicy.allows("GET", path)).as(path).isTrue();
        assertThat(
                        DemoAccessPolicy.allows(
                                "POST",
                                "/api/platform/observability/inspections/ops-rag-service/run"))
                .isTrue();
        for (String path :
                new String[] {
                    "/api/platform/traffic/rules/FLOW/validate",
                    "/api/platform/traffic/rules/FLOW/publish",
                    "/api/platform/traffic/rules/FLOW/rollback",
                    "/api/platform/config-center/new",
                    "/api/platform/observability/topology/layout",
                    "/api/platform/observability/services/ops-rag-service/restart"
                }) {
            assertThat(DemoAccessPolicy.allows("POST", path)).as(path).isFalse();
            assertThat(DemoAccessPolicy.allows("PUT", path)).as(path).isFalse();
            assertThat(DemoAccessPolicy.allows("DELETE", path)).as(path).isFalse();
        }
        assertThat(DemoAccessPolicy.allows("GET", "/api/platform/config-center/secret/raw"))
                .isFalse();
    }

    @Test
    void permitsCurrentWorkspaceReadsWithoutAddingMutationOrActorOverrideRoutes() {
        for (String path :
                new String[] {
                    "/api/tickets/queue",
                    "/api/tickets/queue/summary",
                    "/api/tickets/12/event-lifecycle",
                    "/api/tickets/12/event-lifecycle/recovery-binding",
                    "/api/platform/observability/topology/layout",
                    "/api/platform/observability/services/ops-rag-service/metric-history"
                }) {
            assertThat(DemoAccessPolicy.allows("GET", path)).as(path).isTrue();
            for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"})
                assertThat(DemoAccessPolicy.allows(method, path)).as(method + " " + path).isFalse();
        }
        for (String path :
                new String[] {
                    "/api/tickets/queue/all-users",
                    "/api/tickets/queue/summary/export",
                    "/api/tickets/12/event-lifecycle/recovery-binding/history",
                    "/api/tickets/12/event-lifecycle/../../13/event-lifecycle",
                    "/api/platform/observability/topology/layout/7",
                    "/api/platform/observability/topology/layout/personal",
                    "/api/platform/observability/services/ops-rag-service/metric-history/raw",
                    "/api/platform/operations/host-resources",
                    "/api/platform/operations/host-resources/credentials",
                    "/api/platform/configuration/files"
                }) assertThat(DemoAccessPolicy.allows("GET", path)).as(path).isFalse();
    }
}
