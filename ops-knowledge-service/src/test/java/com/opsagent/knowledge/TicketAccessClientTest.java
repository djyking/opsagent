package com.opsagent.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorAccess;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.OpsPrincipal;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;

/**
 * 验证跨服务权限查询继承原账号令牌并在拒绝时关闭附件检索。
 *
 * @author heyu
 * @since 2026/9/3
 */
class TicketAccessClientTest {
    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESOLVED", "CLOSED"})
    void completedOwnerDrillUsesAuthoritativeTicketAndOriginalUserToken(String status)
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            visitorRequest();
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    "{\"code\":0,\"data\":{"
                                            + completedDrillFields(status)
                                            + "}}"));
            client(server).requireCompletedVisitorDrill(2053);
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/api/tickets/2053");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer visitor-token");
            assertThat(request.getHeader("X-User-Id")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"ownerActorId\":-100",
                "\"ownerActorId\":null",
                "\"sourceType\":\"ALERTMANAGER\"",
                "\"environment\":\"CORE\"",
                "\"affectedCiCode\":\"ops-platform-service\"",
                "\"incidentId\":null",
                "\"incidentId\":\"\"",
                "\"id\":2054"
            })
    void visibleOrPublicTicketCannotBePromotedToOwnIsolatedDrill(String invalidField)
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            visitorRequest();
            String fieldName = invalidField.substring(0, invalidField.indexOf(':'));
            String fields =
                    completedDrillFields("RESOLVED")
                            .replaceAll(
                                    java.util.regex.Pattern.quote(fieldName)
                                            + ":(?:\"[^\"]*\"|-?[0-9]+)",
                                    java.util.regex.Matcher.quoteReplacement(invalidField));
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"code\":0,\"data\":{" + fields + "}}"));
            assertThatThrownBy(() -> client(server).requireCompletedVisitorDrill(2053))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("本人已完成");
        }
    }

    @Test
    void unfinishedDrillCannotBeSavedAsRecoveryKnowledge() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            visitorRequest();
            server.enqueue(
                    new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                    "{\"code\":0,\"data\":{"
                                            + completedDrillFields("PROCESSING")
                                            + "}}"));
            assertThatThrownBy(() -> client(server).requireCompletedVisitorDrill(2053))
                    .hasMessageContaining("完成演练恢复验证");
        }
    }

    private void visitorRequest() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer visitor-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(-99, "visitor", "jwt-id", List.of("DEMO")),
                                null,
                                List.of()));
    }

    private String completedDrillFields(String status) {
        return "\"id\":2053,\"ownerActorId\":-99,\"sourceType\":\"ISOLATED_DRILL\","
                + "\"environment\":\"ISOLATED\",\"affectedCiCode\":\"ops-demo-order-service\","
                + "\"incidentId\":\"drill-owned\",\"status\":\""
                + status
                + "\"";
    }

    @Test
    void shouldRelayOriginalAuthorizationWithoutInventingAnElevatedIdentity() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"code\":0,\"data\":{\"id\":2053}}"));
            server.start();
            var request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer original-user-token");
            request.addHeader("X-Trace-Id", "scope-check");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            client(server).requireVisible(2053);
            var forwarded = server.takeRequest();
            assertThat(forwarded.getPath()).isEqualTo("/api/tickets/2053");
            assertThat(forwarded.getHeader("Authorization")).isEqualTo("Bearer original-user-token");
            assertThat(forwarded.getHeader("X-Trace-Id")).isEqualTo("scope-check");
            assertThat(forwarded.getHeader("X-User-Id")).isNull();
        }
    }

    @Test
    void shouldDenyWithoutTokenAndSanitizeBusinessAccessDenial() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"code\":40300,\"message\":\"internal password=secret\"}"));
            server.start();
            var client = client(server);
            assertThatThrownBy(() -> client.requireVisible(2053)).isInstanceOf(BusinessException.class);
            assertThat(server.getRequestCount()).isZero();
            var request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer user-token");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            assertThatThrownBy(() -> client.requireVisible(2053))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("不可访问")
                    .hasMessageNotContaining("secret");
        }
    }

    @Test
    void shouldFetchAccessibleTicketIdsOnceWithoutPerDocumentRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"code\":0,\"data\":[{\"id\":2053},{\"id\":2055}]}"));
            server.start();
            var principal = new OpsPrincipal(-99, "visitor", "jwt-id", List.of("DEMO"));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, List.of()));
            try {
                var request = new MockHttpServletRequest();
                request.addHeader("Authorization", "Bearer ordinary-user-token");
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                assertThat(client(server).visibleTicketIds()).containsExactlyInAnyOrder(2053L, 2055L);
                assertThat(server.takeRequest().getPath()).isEqualTo("/api/tickets");
                assertThat(server.getRequestCount()).isEqualTo(1);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void internalAudienceMustNotBeRelayedAsAnOrdinaryUserToken() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            var actor = new InternalActorTokens.Context(-99, "visitor", List.of("DEMO"), "run-1",
                    "ops-demo-order-service", Instant.now().plusSeconds(60));
            try (var scope = InternalActorAccess.open(actor)) {
                assertThat(client(server).visibleTicketIds()).isEmpty();
                assertThat(server.getRequestCount()).isZero();
            }
        }
    }

    private TicketAccessClient client(MockWebServer server) {
        return new TicketAccessClient(server.url("/").toString(), mock(DiscoveryClient.class), RestClient.builder());
    }
}
