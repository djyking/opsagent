package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
        when(streaming.open(any(), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            completed.set(call.getArgument(2));
                            failed.set(call.getArgument(3));
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
    void beginAndContextPreparationFailuresAlwaysReleaseEntry() {
        var cause = new IllegalStateException("begin failure");
        when(conversations.begin(anyString(), anyLong(), anyString())).thenThrow(cause);
        assertThatThrownBy(() -> controller.ask("chat", request())).isSameAs(cause);
        verify(scope).failure(cause);
        verify(streaming, never()).open(any(), any(), any(), any());
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
    void contextFailureRetainsOriginalExceptionEvenWhenFailureAuditAlsoFails() {
        var cause = new IllegalStateException("context failure");
        when(conversations.context(anyString(), anyLong())).thenThrow(cause);
        doThrow(new IllegalStateException("audit failure"))
                .when(conversations)
                .fail(anyString(), anyLong(), anyLong(), anyString());
        assertThatThrownBy(() -> controller.ask("chat", request())).isSameAs(cause);
        assertThat(cause.getSuppressed()).hasSize(1);
        verify(scope).failure(cause);
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
