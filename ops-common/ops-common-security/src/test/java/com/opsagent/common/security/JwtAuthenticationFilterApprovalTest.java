package com.opsagent.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实 JWT 经过访客过滤器，审批列表仅开放精确 GET，仍将原身份传给下游所有权校验。
 *
 * @author heyu
 * @since 2026/9/3
 */
class JwtAuthenticationFilterApprovalTest {
    private JwtService jwt;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        JwtProperties properties = new JwtProperties();
        properties.setSecret("approval-filter-test-secret-at-least-32-bytes");
        jwt = new JwtService(properties);
        filter = new JwtAuthenticationFilter(jwt);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEMO", "ROLE_DEMO"})
    void exactPendingGetPassesOriginalVisitorIdentityToDownstream(String role) throws Exception {
        var request = request("GET", "/api/automation/approvals/pending", role);
        request.setQueryString("limit=50");
        var response = new MockHttpServletResponse();
        AtomicReference<Authentication> downstream = new AtomicReference<>();

        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    downstream.set(SecurityContextHolder.getContext().getAuthentication());
                    response.setStatus(204);
                });

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(downstream.get()).isNotNull();
        assertThat(downstream.get().isAuthenticated()).isTrue();
        var principal = (OpsPrincipal) downstream.get().getPrincipal();
        assertThat(principal.userId()).isEqualTo(-7);
        assertThat(principal.roles()).containsExactly(role);
        assertThat(downstream.get().getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactly("ROLE_DEMO");
    }

    @ParameterizedTest
    @CsvSource({
        "POST,/api/automation/approvals/pending",
        "PUT,/api/automation/approvals/pending",
        "PATCH,/api/automation/approvals/pending",
        "DELETE,/api/automation/approvals/pending",
        "HEAD,/api/automation/approvals/pending",
        "GET,/api/automation/approvals",
        "GET,/api/automation/approvals/all",
        "GET,/api/automation/approvals/another-approval",
        "GET,/api/automation/approvals/another-approval/decision",
        "GET,/api/automation/approvals/pending-extra",
        "GET,/api/automation/approvals/pending/",
        "GET,/api/automation/approvals/pending/anything",
        "GET,/api/automation/approvals%2Fpending",
        "GET,/api/automation/new-route"
    })
    void otherMethodsAndApprovalPathsStillStopBeforeDownstream(String method, String path)
            throws Exception {
        var response = new MockHttpServletResponse();
        AtomicReference<Boolean> reached = new AtomicReference<>(false);

        filter.doFilter(request(method, path, "DEMO"), response, (req, res) -> reached.set(true));

        assertThat(reached.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"code\":40300");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEMO", "ROLE_DEMO"})
    void previouslyAllowedExactDecisionRouteStillReachesServiceOwnershipChecks(String role)
            throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(
                request("POST", "/api/automation/approvals/approval-123/decision", role),
                response,
                (req, res) -> response.setStatus(204));

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    private MockHttpServletRequest request(String method, String path, String role) {
        var request = new MockHttpServletRequest(method, path);
        String token = jwt.issue(-7, "visitor", List.of(role)).token();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
