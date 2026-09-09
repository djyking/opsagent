package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Complete conversation streams retain an actual Sentinel entry until settlement, including failure
 * paths.
 *
 * @author heyu
 * @since 2026/9/3
 */
class RagTrafficScopeTest {
    private final RagConversationService conversations = mock(RagConversationService.class);
    private final RagService rag = mock(RagService.class);
    private final RagStreamingService streaming = mock(RagStreamingService.class);
    private final RagRateLimiter limiter = mock(RagRateLimiter.class);
    private final RagRateLimiter.Scope scope = mock(RagRateLimiter.Scope.class);
    private final AtomicReference<Consumer<RagService.Answer>> completed = new AtomicReference<>();
    private final AtomicReference<Consumer<String>> failed = new AtomicReference<>();
    private RagConversationController controller;

    @BeforeEach
    void setup() {
        var actor = new OpsPrincipal(1, "ops", "test", List.of("OPS"));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        controller = new RagConversationController(conversations, rag, streaming, limiter);
        when(limiter.requestScope()).thenReturn(scope);
        when(conversations.begin(anyString(), anyLong(), anyString())).thenReturn(12L);
        when(streaming.openPrepared(any(), any(), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            completed.set(call.getArgument(3));
                            failed.set(call.getArgument(4));
                            return new SseEmitter();
                        });
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void conversationEntryIsNotClosedBeforeStreamCallbackAndClosesAfterPersistence() {
        controller.ask("chat", request());
        verify(scope, never()).close();
        verify(scope, never()).failure(any());
        completed.get().accept(null);
        var order = inOrder(conversations, scope);
        order.verify(conversations).complete("chat", 1, 12, null);
        order.verify(scope).close();
    }

    @Test
    void beginFailuresAlwaysReleaseEntry() {
        var cause = new IllegalStateException("begin failure");
        when(conversations.begin(anyString(), anyLong(), anyString())).thenThrow(cause);
        assertThatThrownBy(() -> controller.ask("chat", request())).isSameAs(cause);
        verify(scope).failure(cause);
        verify(streaming, never()).openPrepared(any(), any(), any(), any(), any());
    }

    @Test
    void failedPersistenceDuringErrorOrCompletionStillSettlesScope() {
        controller.ask("chat", request());
        doThrow(new IllegalStateException("db failure"))
                .when(conversations)
                .fail(anyString(), anyLong(), anyLong(), anyString());
        assertThatThrownBy(() -> failed.get().accept("connection closed"))
                .isInstanceOf(IllegalStateException.class);
        verify(scope).failure(any());
        doThrow(new IllegalStateException("completion failure"))
                .when(conversations)
                .complete(anyString(), anyLong(), anyLong(), any());
        assertThatThrownBy(() -> completed.get().accept(null))
                .isInstanceOf(IllegalStateException.class);
        verify(scope).close();
    }

    @Test
    void streamOpeningFailureRetainsOriginalExceptionEvenWhenFailureAuditAlsoFails() {
        var cause = new IllegalStateException("stream opening failure");
        when(streaming.openPrepared(any(), any(), any(), any(), any())).thenThrow(cause);
        doThrow(new IllegalStateException("audit failure"))
                .when(conversations)
                .fail(anyString(), anyLong(), anyLong(), anyString());
        assertThatThrownBy(() -> controller.ask("chat", request())).isSameAs(cause);
        assertThat(cause.getSuppressed()).hasSize(1);
        verify(scope).failure(cause);
    }

    @Test
    void asynchronousContextFailureStillSettlesScopeWhenFailureAuditAlsoFails() throws Exception {
        when(conversations.context(anyString(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "历史访问被拒绝"));
        doThrow(new IllegalStateException("audit failure"))
                .when(conversations)
                .fail(anyString(), anyLong(), anyLong(), anyString());
        var liveStreaming = new RagStreamingService(rag, new AiProperties(), Runnable::run);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new RagConversationController(
                                        conversations, rag, liveStreaming, limiter))
                        .build();
        var started =
                mvc.perform(
                                post("/api/rag/conversations/chat/stream")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.TEXT_EVENT_STREAM)
                                        .content("{\"question\":\"为什么服务变慢\"}"))
                        .andReturn();
        String body =
                mvc.perform(asyncDispatch(started))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body)
                .contains("event:error", "\"code\":40300", "历史访问被拒绝")
                .doesNotContain("event:done", "event:token", "audit failure");
        verify(conversations).fail("chat", 1, 12, "历史访问被拒绝");
        verify(scope).failure(any());
        verify(conversations, never()).complete(anyString(), anyLong(), anyLong(), any());
        verify(rag, never()).stream(any(), any(), any(), any());
        verify(rag, never())
                .prepareStream(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void realAsyncEntryTracksConcurrencyAndClosesOnlyOnce() {
        var previous = FlowRuleManager.getRules();
        var metrics = new SimpleMeterRegistry();
        try {
            FlowRuleManager.loadRules(List.of());
            var real = new RagRateLimiter(mock(AiBudgetGuard.class), metrics);
            var live = real.requestScope();
            var node = ClusterBuilderSlot.getClusterNode(RagRateLimiter.REQUEST_RESOURCE);
            assertThat(node.curThreadNum()).isGreaterThanOrEqualTo(1);
            int before = node.curThreadNum();
            live.close();
            live.close();
            live.failure(new IllegalStateException("late failure"));
            assertThat(node.curThreadNum()).isEqualTo(before - 1);
            FlowRule block = new FlowRule(RagRateLimiter.REQUEST_RESOURCE);
            block.setGrade(1);
            block.setCount(0);
            FlowRuleManager.loadRules(List.of(block));
            assertThatThrownBy(real::requestScope).isInstanceOf(BusinessException.class);
        } finally {
            FlowRuleManager.loadRules(previous);
            metrics.close();
        }
    }

    private RagConversationController.QuestionRequest request() {
        return new RagConversationController.QuestionRequest("为什么服务变慢", null, null, null, null);
    }
}
