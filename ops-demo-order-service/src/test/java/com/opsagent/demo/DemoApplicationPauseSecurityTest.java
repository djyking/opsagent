package com.opsagent.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application pause remains on fixed internal-token routes, never user JWT privileges.
 *
 * @author heyu
 * @since 2026/9/3
 */
class DemoApplicationPauseSecurityTest {
    @Test
    void pauseAndResumeRejectMissingOrUserCredentialsBeforeReachingTarget() throws Exception {
        String token = "local-test-control-token-with-32-characters";
        var filter = new DemoControlFilter(token);
        for (String suffix : List.of("", "/resume")) {
            String path = "/internal/demo/configuration/order-business/application-pause" + suffix;
            var request = new MockHttpServletRequest("POST", path);
            request.addHeader("Authorization", "Bearer ordinary-user-token");
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> called.set(true));
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(called).isFalse();
            request = new MockHttpServletRequest("POST", path);
            request.addHeader("X-Demo-Control-Token", token);
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called.set(true));
            assertThat(called).isTrue();
        }
    }
}
