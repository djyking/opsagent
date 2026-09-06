package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens;

import feign.RequestTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 内部检索仅处理已授权候选，保留可引用来源，失败不扩大范围或改用无权限查询。
 *
 * @author heyu
 * @since 2026/9/3
 */
class InternalAgentSearchServiceTest {
    private final InternalKnowledgeClient knowledge = mock(InternalKnowledgeClient.class);
    private final RerankService rerank = mock(RerankService.class);
    private final RagRateLimiter rate = mock(RagRateLimiter.class);
    private final InternalActorTokens tokens =
            new InternalActorTokens("search-test-internal-secret-over-32-bytes");

    @Test
    void shouldRelayScopedAudienceAndRedactSecretsBeforeRankingAndEvidence() {
        when(knowledge.search(anyString(), any()))
                .thenReturn(
                        new KnowledgeClient.Envelope<>(
                                0,
                                "ok",
                                List.of(
                                        Map.of(
                                                "chunkId",
                                                10,
                                                "documentId",
                                                7,
                                                "chunkIndex",
                                                0,
                                                "documentName",
                                                "已授权.md",
                                                "content",
                                                "Redis排查步骤 password=do-not-send Authorization:"
                                                        + " Bearer private-value")),
                                "trace"));
        when(rerank.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(
                        invocation ->
                                new RerankService.Outcome(invocation.getArgument(1), false, null));
        var result =
                service()
                        .search(new InternalAgentDtos.SearchRequest("Redis排查", 5), actor(), tokens);
        assertThat(result.evidence())
                .contains("Redis排查步骤", "[REDACTED]")
                .doesNotContain("do-not-send", "private-value");
        assertThat(result.citations())
                .extracting(RagService.Source::documentId)
                .containsExactly(7L);
        var authorization = ArgumentCaptor.forClass(String.class);
        verify(knowledge).search(authorization.capture(), any());
        assertThat(tokens.verify(authorization.getValue(), "knowledge").userId()).isEqualTo(10);
        assertThatThrownBy(() -> tokens.verify(authorization.getValue(), "rag"))
                .isInstanceOf(BusinessException.class);
        verify(rate).check();
    }

    @Test
    void shouldFailClosedWhenPermissionRetrievalFailsAndRejectMissingInternalAuthorization() {
        when(knowledge.search(anyString(), any()))
                .thenReturn(new KnowledgeClient.Envelope<>(40300, "denied", null, "trace"));
        assertThatThrownBy(
                        () ->
                                service()
                                        .search(
                                                new InternalAgentDtos.SearchRequest("Redis", 5),
                                                actor(),
                                                tokens))
                .isInstanceOf(BusinessException.class)
                .hasMessage("AUTHORIZED_RETRIEVAL_DENIED");
        verifyNoInteractions(rerank);
        var model = mock(InternalAgentModelService.class);
        var controller =
                new InternalAgentController(
                        model,
                        service(),
                        "search-test-internal-secret-over-32-bytes",
                        "http://unused.test");
        assertThatThrownBy(() -> controller.models(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("INTERNAL_AUTH_INVALID");
        verifyNoInteractions(model);
    }

    private InternalAgentSearchService service() {
        return new InternalAgentSearchService(
                knowledge,
                rerank,
                new ContextAssembler(new RagProperties(), new SimpleMeterRegistry()),
                rate);
    }

    @Test
    void shouldPreserveDestinationSpecificCredentialWhenOriginalRequestHasAnotherAudience() {
        var incoming = new MockHttpServletRequest();
        incoming.addHeader("Authorization", "Bearer incoming-rag-only");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(incoming));
        try {
            var outgoing = new RequestTemplate();
            outgoing.header("Authorization", "Bearer issued-for-knowledge");
            new FeignSecurityConfiguration().tokenRelay().apply(outgoing);
            assertThat(outgoing.headers().get("Authorization"))
                    .containsExactly("Bearer issued-for-knowledge");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private InternalActorTokens.Context actor() {
        return new InternalActorTokens.Context(
                10, "user", List.of("USER"), "run", "demo-order", Instant.now().plusSeconds(60));
    }
}
