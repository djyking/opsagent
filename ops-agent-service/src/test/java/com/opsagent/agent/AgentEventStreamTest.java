package com.opsagent.agent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.web.GlobalExceptionHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

/**
 * SSE 握手在尚未打开流时保留准确 JSON 拒绝响应，严格 Accept 不能把授权错误变成登录失效。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AgentEventStreamTest {
    private AgentTestSupport fixture;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        fixture = new AgentTestSupport();
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AgentEventStream(fixture.store, fixture.clients))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        var principal = new OpsPrincipal(-123, "visitor", "unit-session", List.of("DEMO"));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void strictSseAcceptKeepsForeignRunForbiddenAsJson() throws Exception {
        String foreignId = fixture.create("foreign-sse", 1);

        mvc.perform(
                        get("/api/automation/runs/" + foreignId + "/stream")
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void strictSseAcceptKeepsMissingIdentityUnauthenticatedAsJson() throws Exception {
        SecurityContextHolder.clearContext();

        mvc.perform(
                        get("/api/automation/runs/foreign-run/stream")
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40100));
    }
}
