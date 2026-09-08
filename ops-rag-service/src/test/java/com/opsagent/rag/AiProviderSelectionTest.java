package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * 验证可选模型目录、明确选择与实际同步/SSE路由，不通过默认供应商伪造切换。
 *
 * @author heyu
 * @since 2026/9/3
 */
class AiProviderSelectionTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldOnlyOfferValidConfiguredProvidersAndNeverSerializeSecrets() throws Exception {
        AiProperties ai = configured();
        var invalid = new AiProperties.ProviderSettings();
        invalid.setApiKey("private-unit-test-key");
        invalid.setModel("kimi-model");
        invalid.setBaseUrl("not-a-url");
        ai.getProviders().put("kimi", invalid);
        var catalog = new AiProviderCatalogController(ai).providers().data();
        assertThat(catalog.providers())
                .filteredOn(AiProviderCatalogController.Provider::available)
                .extracting(AiProviderCatalogController.Provider::provider)
                .containsExactly("deepseek", "openai");
        String json = new ObjectMapper().writeValueAsString(catalog);
        assertThat(json)
                .doesNotContain("apiKey", "baseUrl", "private-unit-test-key", "example.test");
        assertThat(catalog.defaultProvider()).isEqualTo("deepseek");
        ai.setEnabled(false);
        assertThat(new AiProviderCatalogController(ai).providers().data().providers())
                .noneMatch(AiProviderCatalogController.Provider::available);
    }

    @Test
    void shouldRouteExplicitSyncAndStreamToOpenAiAndPreserveSharedBudget() {
        AiProperties ai = configured();
        LlmClient deepseek = client("deepseek");
        LlmClient openai = client("openai");
        var router = new LlmClientRouter(ai, List.of(deepseek, openai));
        var budget = mock(AiBudgetGuard.class);
        var usage = mock(AiUsageRepository.class);
        var invocation = new LlmInvocationService(router, new SimpleMeterRegistry(), usage, budget);
        var principal = new OpsPrincipal(17, "test", "test", List.of("USER"));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, "", List.of()));
        var request = new LlmRequest("system", "question", 100);
        var result = new LlmResult("selected answer", "openai", "openai-model", 1, 2);
        when(openai.generate(request)).thenReturn(result);
        when(openai.stream(eq(request), any())).thenReturn(result);
        assertThat(invocation.invoke("openai", "question", request).result().provider())
                .isEqualTo("openai");
        assertThat(
                        invocation.stream(
                                        "openai",
                                        "question",
                                        request,
                                        delta -> {},
                                        invocation.currentContext())
                                .result()
                                .provider())
                .isEqualTo("openai");
        verify(budget, times(2)).acquire();
        verify(deepseek, never()).generate(any());
        verify(deepseek, never()).stream(any(), any());
    }

    @Test
    void shouldRejectUnavailableOrUnknownSelectionWithoutTryingAnotherProvider() {
        AiProperties ai = configured();
        LlmClient deepseek = client("deepseek");
        var router = new LlmClientRouter(ai, List.of(deepseek));
        assertThatThrownBy(() -> router.selected("kimi"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未配置");
        assertThatThrownBy(() -> router.selected("http://another-provider"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持");
        verify(deepseek, never()).generate(any());
        verify(deepseek, never()).stream(any(), any());
    }

    @Test
    void shouldCarryProviderFromRequestThroughPreparedPlanAndBothRagPaths() {
        AiProperties ai = configured();
        var knowledge = mock(KnowledgeClient.class);
        when(knowledge.search(anyString(), anyInt(), nullable(Long.class)))
                .thenReturn(new KnowledgeClient.Envelope<>(0, "ok", List.of(), "test"));
        var rerank = mock(RerankService.class);
        when(rerank.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(new RerankService.Outcome(List.of(), false, null));
        var prompts = mock(PromptBuilder.class);
        var request = new LlmRequest("system", "question", 100);
        when(prompts.build(anyString(), any(ContextAssembler.AssembledContext.class)))
                .thenReturn(request);
        var invocation = mock(LlmInvocationService.class);
        var result =
                new LlmInvocationService.Invocation(
                        new LlmResult("answer", "openai", "openai-model", 1, 2), 10);
        when(invocation.invoke(
                        eq("openai"),
                        anyString(),
                        eq(
                                request.withPriorReservedTokens(
                                        com.opsagent.common.core.QueryEmbeddingBudget.reserve(
                                                "解释连接池")))))
                .thenReturn(result);
        when(invocation.stream(eq("openai"), anyString(), any(LlmRequest.class), any(), any()))
                .thenReturn(result);
        var properties = new RagProperties();
        var metrics = new SimpleMeterRegistry();
        var rag =
                new RagService(
                        knowledge,
                        properties,
                        ai,
                        prompts,
                        invocation,
                        new CitationValidator(),
                        rerank,
                        new ContextAssembler(properties, metrics),
                        metrics,
                        mock(CmdbAnswerService.class),
                        mock(OperationsAnswerService.class));
        var plan = rag.prepareStream("解释连接池", 5, null, null, "历史", "openai");
        assertThat(plan.provider()).isEqualTo("openai");
        assertThat(rag.ask("解释连接池", 5, null, null, "openai").provider()).isEqualTo("openai");
        assertThat(
                        rag.stream(
                                        plan,
                                        delta -> {},
                                        new LlmInvocationService.AuditContext(17, "trace"))
                                .provider())
                .isEqualTo("openai");
        verify(invocation, never()).invoke(anyString(), any());
        verify(invocation, never()).stream(anyString(), any(), any(), any());
    }

    static AiProperties configured() {
        AiProperties ai = new AiProperties();
        ai.setEnabled(true);
        for (String name : List.of("deepseek", "openai")) {
            var settings = new AiProperties.ProviderSettings();
            settings.setApiKey("private-unit-test-key");
            settings.setBaseUrl("https://example.test/v1");
            settings.setModel(name + "-model");
            ai.getProviders().put(name, settings);
        }
        return ai;
    }

    private LlmClient client(String name) {
        var client = mock(LlmClient.class);
        when(client.provider()).thenReturn(name);
        when(client.model()).thenReturn(name + "-model");
        return client;
    }
}
