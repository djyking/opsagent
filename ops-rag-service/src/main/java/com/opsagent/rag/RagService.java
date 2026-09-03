package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 编排权限检索、Prompt 构建、模型调用和真实来源返回。
 *
 * @author heyu
 * @since 2026/8/27
 */
@Service
public class RagService {
    private final KnowledgeClient knowledge;
    private final RagProperties ragProperties;
    private final AiProperties aiProperties;
    private final PromptBuilder promptBuilder;
    private final LlmInvocationService invocationService;
    private final CitationValidator citationValidator;

    RagService(
            KnowledgeClient knowledge,
            RagProperties ragProperties,
            AiProperties aiProperties,
            PromptBuilder promptBuilder,
            LlmInvocationService invocationService,
            CitationValidator citationValidator) {
        this.knowledge = knowledge;
        this.ragProperties = ragProperties;
        this.aiProperties = aiProperties;
        this.promptBuilder = promptBuilder;
        this.invocationService = invocationService;
        this.citationValidator = citationValidator;
    }

    Answer ask(String question, Integer requestedTopK) {
        return ask(question, requestedTopK, null);
    }

    Answer ask(String question, Integer requestedTopK, Long documentId) {
        StreamPlan plan = prepareStream(question, requestedTopK, documentId);
        if (plan.immediate() != null) {
            return plan.immediate();
        }
        try {
            LlmInvocationService.Invocation invocation = invocationService.invoke(
                    question, plan.request());
            return complete(plan, invocation);
        } catch (AiProviderException exception) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, exception.getMessage());
        }
    }

    StreamPlan prepareStream(String question, Integer requestedTopK, Long documentId) {
        if (credentialExtractionQuestion(question)) {
            return StreamPlan.completed(question, noEvidence());
        }
        int topK = ragProperties.limit(requestedTopK);
        List<RetrievedChunk> chunks;
        try {
            var result = knowledge.search(question, topK, documentId);
            List<Map<String, Object>> data = result.data() == null ? List.of() : result.data();
            chunks = data.stream()
                    .map(RetrievedChunk::from)
                    .filter(chunk -> chunk.chunkId() > 0)
                    .toList();
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "知识检索暂时不可用");
        }
        List<Source> sources = chunks.stream().map(Source::from).toList();
        if (chunks.isEmpty() && internalFactQuestion(question)) {
            return StreamPlan.completed(question, noEvidence());
        }
        if (!aiProperties.isEnabled()) {
            return StreamPlan.completed(question, localFallback(chunks, sources));
        }
        return new StreamPlan(
                question, chunks, sources, promptBuilder.build(question, chunks), null);
    }

    Answer stream(
            StreamPlan plan,
            Consumer<String> onDelta,
            LlmInvocationService.AuditContext context) {
        if (plan.immediate() != null) {
            onDelta.accept(plan.immediate().answer());
            return plan.immediate();
        }
        LlmInvocationService.Invocation invocation = invocationService.stream(
                plan.question(), plan.request(), onDelta, context);
        return complete(plan, invocation);
    }

    LlmInvocationService.AuditContext auditContext() {
        return invocationService.currentContext();
    }

    private Answer complete(StreamPlan plan, LlmInvocationService.Invocation invocation) {
        LlmResult result = invocation.result();
        return new Answer(
                citationValidator.validate(result.text(), plan.chunks()),
                plan.sources(),
                result.provider(),
                result.model(),
                result.inputTokens(),
                result.outputTokens(),
                invocation.latencyMs());
    }

    private Answer localFallback(List<RetrievedChunk> chunks, List<Source> sources) {
        if (chunks.isEmpty()) {
            return new Answer(
                    "当前检索到的知识库内容不足以确认该问题。",
                    sources,
                    "disabled",
                    "none",
                    0,
                    0,
                    0);
        }
        RetrievedChunk first = chunks.get(0);
        String content = first.content();
        String excerpt = content.substring(0, Math.min(content.length(), 500));
        return new Answer(
                "AI 生成功能未启用。以下是最相关知识片段：\n" + excerpt + " [chunk:" + first.chunkId() + "]",
                sources,
                "disabled",
                "retrieval-only",
                0,
                0,
                0);
    }

    private Answer noEvidence() {
        return new Answer(
                "当前检索到的知识库内容不足以确认该问题。",
                List.of(),
                "none",
                "none",
                0,
                0,
                0);
    }

    private boolean credentialExtractionQuestion(String question) {
        String lower = question.toLowerCase();
        if (question.contains("文档")
                && (question.contains("意思")
                        || question.contains("说了什么")
                        || question.contains("这句话"))) {
            return false;
        }
        boolean secret = lower.contains("password")
                || lower.contains("api key")
                || lower.contains("token")
                || question.contains("密码")
                || question.contains("口令")
                || question.contains("密钥")
                || question.contains("令牌");
        boolean requestingValue = lower.contains("root")
                || question.contains("多少")
                || question.contains("是什么")
                || question.contains("告诉")
                || question.contains("给我")
                || question.contains("查询")
                || question.contains("输出");
        return secret && requestingValue;
    }

    private boolean internalFactQuestion(String question) {
        String lower = question.toLowerCase();
        return lower.contains("opsagent")
                || question.contains("生产")
                || question.contains("当前")
                || question.contains("密码")
                || question.contains("账号")
                || question.contains("地址")
                || question.contains("本项目");
    }

    /**
     * 检索增强问答结果。
     *
     * @author heyu
     * @since 2026/8/27
     */
    record Answer(
            String answer,
            List<Source> references,
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            long latencyMs) {}

    /**
     * 保存请求线程已完成的检索结果，避免 SSE 工作线程丢失 Feign Token Relay 上下文。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record StreamPlan(
            String question,
            List<RetrievedChunk> chunks,
            List<Source> sources,
            LlmRequest request,
            Answer immediate) {
        static StreamPlan completed(String question, Answer answer) {
            return new StreamPlan(question, List.of(), answer.references(), null, answer);
        }
    }

    /**
     * 返回由程序根据真实检索结果生成的来源，而不是信任模型自行声明的引用。
     *
     * @author heyu
     * @since 2026/9/1
     */
    record Source(
            long chunkId,
            long documentId,
            int chunkIndex,
            String documentName,
            Integer page,
            Integer version,
            String updateTime,
            double score) {
        static Source from(RetrievedChunk chunk) {
            return new Source(
                    chunk.chunkId(),
                    chunk.documentId(),
                    chunk.chunkIndex(),
                    chunk.documentName(),
                    chunk.page(),
                    chunk.version(),
                    chunk.updateTime(),
                    chunk.score());
        }
    }
}
