package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 验证预算在同步与 SSE 的实际供应商调用之前生效，失败后不会泄漏并发名额。
 *
 * @author heyu
 * @since 2026/9/3
 */
class LlmBudgetIntegrationTest {
    private final LlmClient client = mock(LlmClient.class);
    private final LlmRequest request = new LlmRequest("system", "test", 100);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private AiBudgetGuard budget;
    private LlmInvocationService service;

    @BeforeEach
    void setUp() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:llm-budget-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        budget = new AiBudgetGuard(jdbc, new RagProperties(), true, 1, 2);
        budget.initialize();
        LlmClientRouter router = mock(LlmClientRouter.class);
        when(router.selected()).thenReturn(client);
        service = new LlmInvocationService(router, metrics, mock(AiUsageRepository.class), budget);
        var user = new OpsPrincipal(10, "demo", "test", List.of("USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "", List.of()));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        metrics.close();
    }

    @Test
    void shouldBlockBothGenerationPathsBeforeCallingProviderWhenBudgetIsExhausted() {
        budget.acquire().close();
        budget.acquire().close();
        assertThatThrownBy(() -> service.invoke(client, "test", request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("今日");
        assertThatThrownBy(() -> service.stream("test", request, delta -> {}, service.currentContext()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("今日");
        verifyNoInteractions(client);
    }

    @Test
    void shouldReleaseConcurrencyForBothPathsEvenWhenProviderThrowsUnexpectedly() {
        when(client.generate(request)).thenThrow(new IllegalStateException("sync test failure"));
        when(client.stream(eq(request), any())).thenThrow(new IllegalStateException("stream test failure"));
        assertThatThrownBy(() -> service.invoke(client, "test", request)).hasMessage("sync test failure");
        assertThatThrownBy(() -> service.stream("test", request, delta -> {}, service.currentContext()))
                .hasMessage("stream test failure");
        verify(client).generate(request);
        verify(client).stream(eq(request), any());
    }
}
