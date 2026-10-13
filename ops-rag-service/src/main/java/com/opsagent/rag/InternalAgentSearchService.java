package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复用知识权限过滤、Rerank 和 ContextAssembler，仅返回有界证据，不再生成模型答案。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class InternalAgentSearchService {
    private final InternalKnowledgeClient knowledge;
    private final RerankService rerank;
    private final ContextAssembler assembler;
    private final RagRateLimiter rate;

    InternalAgentSearchService(
            InternalKnowledgeClient knowledge,
            RerankService rerank,
            ContextAssembler assembler,
            RagRateLimiter rate) {
        this.knowledge = knowledge;
        this.rerank = rerank;
        this.assembler = assembler;
        this.rate = rate;
    }

    InternalAgentDtos.SearchResponse search(
            InternalAgentDtos.SearchRequest request,
            InternalActorTokens.Context actor,
            InternalActorTokens tokens) {
        rate.check();
        KnowledgeClient.Envelope<List<Map<String, Object>>> response;
        try {
            response = knowledge.search("Bearer " + tokens.issue("knowledge", actor), request);
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "AUTHORIZED_RETRIEVAL_UNAVAILABLE");
        }
        if (response == null || response.code() != 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "AUTHORIZED_RETRIEVAL_DENIED");
        }
        List<RetrievedChunk> candidates =
                (response.data() == null ? List.<Map<String, Object>>of() : response.data())
                        .stream()
                                .limit(30)
                                .map(this::safeRow)
                                .map(RetrievedChunk::from)
                                .filter(chunk -> chunk.chunkId() > 0 && chunk.documentId() > 0)
                                .toList();
        var ranked = rerank.rerank(request.query(), candidates, request.topK());
        var context = assembler.assemble(ranked.chunks(), candidates, false);
        return new InternalAgentDtos.SearchResponse(
                context.text(), context.sources().stream().map(RagService.Source::from).toList());
    }

    private Map<String, Object> safeRow(Map<String, Object> row) {
        Map<String, Object> safe = new LinkedHashMap<>(row);
        for (String key : List.of("content", "documentName", "headingPath")) {
            String value = String.valueOf(row.getOrDefault(key, ""));
            value =
                    value.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~-]+", "Bearer [REDACTED]")
                            .replaceAll(
                                    "(?i)((?:api[-_]?key|password|secret|token|密码|密钥)\\s*[=:：]\\s*)[^\\s,;，；]+",
                                    "$1[REDACTED]");
            safe.put(key, value.substring(0, Math.min(value.length(), 20000)));
        }
        return safe;
    }
}
