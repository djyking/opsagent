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
}
