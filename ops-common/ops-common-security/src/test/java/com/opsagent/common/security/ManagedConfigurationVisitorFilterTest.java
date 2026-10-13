package com.opsagent.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 纳管配置只开放固定目录读取，真实访客 JWT 不能发布、回退或探测其他配置。
 *
 * @author heyu
 * @since 2026/9/3
 */
class ManagedConfigurationVisitorFilterTest {
    private static final String ROOT = "/api/platform/configuration/managed";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void fixedReadRoutesPreserveVisitorIdentity() throws Exception {
        for (String role : List.of("DEMO", "ROLE_DEMO")) {
            for (String suffix :
                    List.of(
                            "",
                            "/order-business",
                            "/order-runtime",
                            "/order-business/history",
                            "/order-runtime/history")) {
                assertThat(invoke("GET", ROOT + suffix, role)).isTrue();
                var principal =
                        (OpsPrincipal)
                                SecurityContextHolder.getContext()
                                        .getAuthentication()
                                        .getPrincipal();
                assertThat(principal.userId()).isEqualTo(-23);
            }
        }
    }

    @Test
    void writesAndUnmanagedReadsNeverReachController() throws Exception {
        for (String suffix :
                List.of(
                        "/order-business/publish",
                        "/order-business/rollback",
                        "/order-business/validate",
                        "/order-runtime/publish",
                        "")) {
            for (String method : List.of("POST", "PUT", "DELETE", "PATCH")) {
                assertThat(invoke(method, ROOT + suffix, "DEMO")).isFalse();
            }
        }
        for (String suffix :
                List.of(
                        "/",
                        "/secrets",
                        "/order-business/secret",
                        "/order-runtime/raw",
                        "/order-business-extra",
                        "/order-business/history/1",
                        "-extra")) {
            assertThat(invoke("GET", ROOT + suffix, "DEMO")).isFalse();
        }
    }

    private boolean invoke(String method, String path, String role) throws Exception {
        clear();
        var properties = new JwtProperties();
        properties.setSecret("managed-config-filter-test-secret-at-least-32-bytes");
        var jwt = new JwtService(properties);
        var request = new MockHttpServletRequest(method, path);
        request.addHeader(
                "Authorization", "Bearer " + jwt.issue(-23, "visitor", List.of(role)).token());
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        new JwtAuthenticationFilter(jwt, principal -> {})
                .doFilter(request, response, (req, res) -> reached.set(true));
        if (!reached.get()) {
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("\"code\":40300");
        }
        return reached.get();
    }
}
