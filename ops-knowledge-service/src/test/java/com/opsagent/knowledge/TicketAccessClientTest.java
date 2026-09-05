package com.opsagent.knowledge;

import com.opsagent.common.core.BusinessException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 验证跨服务权限查询继承原账号令牌并在拒绝时关闭附件检索。
 *
 * @author heyu
 * @since 2026/9/3
 */
class TicketAccessClientTest {
    @AfterEach
    void cleanup() { RequestContextHolder.resetRequestAttributes(); }

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

    private TicketAccessClient client(MockWebServer server) {
        return new TicketAccessClient(server.url("/").toString(), mock(DiscoveryClient.class), RestClient.builder());
    }
}
