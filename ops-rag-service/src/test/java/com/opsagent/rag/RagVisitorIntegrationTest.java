package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.opsagent.common.security.JwtAuthenticationFilter;
import com.opsagent.common.security.JwtProperties;
import com.opsagent.common.security.JwtService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

/**
 * 使用真实访客JWT过滤器和MVC SSE验证实时源失败仍明确返回不可用，不伪造运行状态。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RagVisitorIntegrationTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void visitorCanReceiveExplicitUnavailableSseWithoutKnowledgeOrModelFallback() throws Exception {
        var platform = mock(PlatformClient.class);
        when(platform.operations())
                .thenThrow(new IllegalStateException("private-upstream-password"));
        var knowledge = mock(KnowledgeClient.class);
        var invocation = mock(LlmInvocationService.class);
        var ai = AiProviderSelectionTest.configured();
        var props = new RagProperties();
        var metrics = new SimpleMeterRegistry();
        var rag =
                new RagService(
                        knowledge,
                        props,
                        ai,
                        mock(PromptBuilder.class),
                        invocation,
                        new CitationValidator(),
                        mock(RerankService.class),
                        new ContextAssembler(props, metrics),
                        metrics,
                        new CmdbAnswerService(platform),
                        new OperationsAnswerService(platform, ai));
        var streaming = new RagStreamingService(rag, ai, Runnable::run);
        var jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-only-negative-visitor-signature-secret-2026");
        var jwt = new JwtService(jwtProperties);
        String token =
                jwt.issue(
                                -8_007_199_254_740_991L,
                                "访客",
                                List.of("DEMO"),
                                java.time.Duration.ofMinutes(30))
                        .token();
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new RagController(
                                        rag,
                                        new RagRateLimiter(mock(AiBudgetGuard.class), metrics),
                                        streaming))
                        .addFilters(new JwtAuthenticationFilter(jwt, principal -> {}))
                        .build();
        var started =
                mvc.perform(
                                post("/api/rag/stream")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType("application/json")
                                        .content(
                                                "{\"question\":\"当前服务健康与内存趋势\",\"provider\":\"openai\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        String body =
                mvc.perform(asyncDispatch(started))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body)
                .contains(
                        "event:done",
                        "OPERATIONS_UNAVAILABLE",
                        "\"provider\":\"operations\"",
                        "\"references\":[]")
                .doesNotContain(
                        "private-upstream-password",
                        "KNOWLEDGE_DOCUMENT",
                        "\"provider\":\"openai\"");
        verifyNoInteractions(knowledge);
        verify(invocation, never()).invoke(anyString(), any());
        verify(invocation, never()).stream(anyString(), any(), any(), any());
        verify(invocation, never()).stream(anyString(), anyString(), any(), any(), any());
        assertThat(
                        com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot
                                .getClusterNode(RagRateLimiter.REQUEST_RESOURCE)
                                .curThreadNum())
                .isZero();
        metrics.close();
    }
}
