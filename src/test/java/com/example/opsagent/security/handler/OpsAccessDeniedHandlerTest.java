package com.example.opsagent.security.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

/**
 * 验证权限不足时由独立处理器返回统一 403 JSON。
 *
 * @author heyu
 * @since 2026/8/15
 */
class OpsAccessDeniedHandlerTest {

    @Test
    void shouldWriteForbiddenResponse() throws Exception {
        OpsSecurityResponseWriter writer = new OpsSecurityResponseWriter(new ObjectMapper());
        OpsAccessDeniedHandler handler = new OpsAccessDeniedHandler(writer);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"code\":403", "\"message\":\"没有访问权限\"");
    }
}
