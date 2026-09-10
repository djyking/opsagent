package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.web.GlobalExceptionHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

/**
 * 验证流开始前的 HTTP 错误与异步准备阶段的 SSE 错误均保留原业务码。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RagRequestExceptionHandlerTest {
    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {
                "NOT_FOUND",
                "FORBIDDEN",
                "UNAUTHENTICATED",
                "VALIDATION",
                "MIDDLEWARE_UNAVAILABLE"
            })
    void streamEntryErrorsKeepTheirRealHttpStatus(ErrorCode code) throws Exception {
        var rag = mock(RagService.class);
        var limiter = mock(RagRateLimiter.class);
        var streaming = mock(RagStreamingService.class);
        var conversations = mock(RagConversationService.class);
        when(conversations.owned(anyString(), anyLong()))
                .thenThrow(new BusinessException(code, "范围不可读取"));
        var mvc = mvc(conversations, rag, streaming, limiter);
        mvc.perform(question())
                .andExpect(status().is(code.code() / 100))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code.code()))
                .andExpect(jsonPath("$.message").value("范围不可读取"));
        verifyNoInteractions(rag, streaming, limiter);
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {
                "NOT_FOUND",
                "FORBIDDEN",
                "UNAUTHENTICATED",
                "VALIDATION",
                "MIDDLEWARE_UNAVAILABLE"
            })
    void asynchronousPreparationErrorsKeepTheirOriginalSseCode(ErrorCode code) throws Exception {
        var rag = mock(RagService.class);
        var limiter = mock(RagRateLimiter.class);
        var scope = mock(RagRateLimiter.Scope.class);
        when(limiter.requestScope()).thenReturn(scope);
        when(rag.prepareStream(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(code, "范围不可读取"));
        var streaming = new RagStreamingService(rag, new AiProperties(), Runnable::run);
        var conversations = mock(RagConversationService.class);
        var mvc = mvc(conversations, rag, streaming, limiter);
        var started = mvc.perform(question()).andReturn();
        String body =
                mvc.perform(asyncDispatch(started))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body)
                .contains("event:status", "\"phase\":\"preparing\"", "event:error")
                .contains("\"code\":" + code.code(), "范围不可读取")
                .doesNotContain("event:token", "event:done");
        verify(conversations).fail("acceptance", -42, 0, "范围不可读取");
        verify(scope).failure(any());
        verify(rag, org.mockito.Mockito.never()).stream(any(), any(), any(), any());
        verify(conversations, org.mockito.Mockito.never())
                .complete(anyString(), anyLong(), anyLong(), any());
    }

    private MockMvc mvc(
            RagConversationService conversations,
            RagService rag,
            RagStreamingService streaming,
            RagRateLimiter limiter) {
        var principal = new OpsPrincipal(-42, "user", "acceptance", List.of("DEMO"));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        return MockMvcBuilders.standaloneSetup(
                        new RagConversationController(conversations, rag, streaming, limiter))
                .setControllerAdvice(new RagRequestExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    private MockHttpServletRequestBuilder question() {
        return post("/api/rag/conversations/acceptance/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(
"""
{"question":"当前服务健康吗","observabilityContext":
{"service":"acceptance-missing-service","environment":"PROD","timeRange":"15m"}}
""");
    }
}
