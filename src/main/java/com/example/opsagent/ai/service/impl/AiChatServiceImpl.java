package com.example.opsagent.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.ai.client.AiModelClient;
import com.example.opsagent.ai.client.AiModelResponse;
import com.example.opsagent.ai.dao.AiChatLogDao;
import com.example.opsagent.ai.dto.AiChatLogQueryRequest;
import com.example.opsagent.ai.dto.AiChatRequest;
import com.example.opsagent.ai.entity.AiChatLog;
import com.example.opsagent.ai.entity.AiQaReference;
import com.example.opsagent.ai.retrieval.ChunkRetrievalService;
import com.example.opsagent.ai.retrieval.ScoredChunk;
import com.example.opsagent.ai.service.AiChatService;
import com.example.opsagent.ai.service.AiQaReferenceService;
import com.example.opsagent.ai.vo.AiChatLogVO;
import com.example.opsagent.ai.vo.AiChatVO;
import com.example.opsagent.ai.vo.AiReferenceVO;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.common.exception.BusinessException;
import com.example.opsagent.common.exception.ErrorCode;
import com.example.opsagent.document.entity.Document;
import com.example.opsagent.document.entity.DocumentChunk;
import com.example.opsagent.document.enums.DocumentParseStatus;
import com.example.opsagent.document.service.DocumentChunkService;
import com.example.opsagent.document.service.DocumentService;
import com.example.opsagent.security.current.CurrentUserContext;
import com.example.opsagent.ticket.service.TicketService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实现事务外模型调用、限定工单的 Top K 检索以及问答与引用短事务保存。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl extends ServiceImpl<AiChatLogDao, AiChatLog>
        implements AiChatService {

    private static final String SYSTEM_PROMPT =
            """
            你是运维文档问答助手。
            优先并仅根据提供的文档上下文回答。
            如果上下文中无法确认答案，明确说明“无法从当前文档中确认”。
            不要编造命令执行结果、服务器状态、未提供的配置或不存在的引用。
            回答应尽量使用 [chunk-id] 指出引用片段。
            """;

    private final ChunkRetrievalService retrievalService;
    private final AiModelClient aiModelClient;
    private final AiQaReferenceService referenceService;
    private final DocumentChunkService chunkService;
    private final DocumentService documentService;
    private final TicketService ticketService;
    private final CurrentUserContext currentUser;
    private final TransactionTemplate transactionTemplate;

    @Override
    public AiChatVO ask(Long ticketId, AiChatRequest request) {
        ticketService.requireAccessibleTicket(ticketId);
        validateDocumentScope(ticketId, request.getDocumentId());
        String question = request.getQuestion().trim();
        List<ScoredChunk> chunks =
                retrievalService.retrieve(
                        ticketId, request.getDocumentId(), question, request.getTopK());
        if (chunks.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "没有检索到可用的文档上下文");
        }
        String context =
                chunks.stream()
                        .map(
                                candidate ->
                                        "[chunk-"
                                                + candidate.chunk().getId()
                                                + "] "
                                                + candidate.chunk().getContent())
                        .collect(Collectors.joining("\n\n"));
        String userPrompt = "文档上下文：\n" + context + "\n\n问题：" + question;
        long start = System.nanoTime();
        AiModelResponse modelResponse;
        try {
            modelResponse = aiModelClient.chat(SYSTEM_PROMPT, userPrompt);
        } catch (RuntimeException exception) {
            long costTimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            saveFailed(ticketId, request.getDocumentId(), question, costTimeMs, exception);
            log.error("AI 模型调用失败，ticketId={}", ticketId, exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 模型调用失败");
        }
        long costTimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        AiChatLog record =
                saveSuccess(
                        ticketId,
                        request.getDocumentId(),
                        question,
                        modelResponse,
                        costTimeMs,
                        chunks);
        return toChatVO(record, chunks);
    }

    @Override
    public PageResponse<AiChatLogVO> pageQuestions(Long ticketId, AiChatLogQueryRequest request) {
        ticketService.requireAccessibleTicket(ticketId);
        validatePage(request.getPageNum(), request.getPageSize());
        LambdaQueryWrapper<AiChatLog> query =
                new LambdaQueryWrapper<AiChatLog>()
                        .eq(AiChatLog::getTicketId, ticketId)
                        .eq(
                                request.getDocumentId() != null,
                                AiChatLog::getDocumentId,
                                request.getDocumentId())
                        .orderByDesc(AiChatLog::getCreateTime);
        Page<AiChatLog> page = page(new Page<>(request.getPageNum(), request.getPageSize()), query);
        return PageResponse.from(page, record -> toLogVO(record, List.of()));
    }

    @Override
    public AiChatLogVO questionDetail(Long id) {
        AiChatLog record = getById(id);
        if (record == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "问答记录不存在");
        }
        ticketService.requireAccessibleTicket(record.getTicketId());
        return toLogVO(record, loadReferences(record.getId()));
    }

    private void validateDocumentScope(Long ticketId, Long documentId) {
        if (documentId == null) {
            return;
        }
        Document document = documentService.requireAccessibleDocument(documentId);
        if (!ticketId.equals(document.getTicketId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档不属于当前工单");
        }
        if (!DocumentParseStatus.SUCCESS.name().equals(document.getParseStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档尚未解析完成");
        }
    }

    private AiChatLog saveSuccess(
            Long ticketId,
            Long documentId,
            String question,
            AiModelResponse modelResponse,
            long costTimeMs,
            List<ScoredChunk> chunks) {
        AiChatLog saved =
                transactionTemplate.execute(
                        status -> {
                            AiChatLog record = new AiChatLog();
                            record.setTicketId(ticketId);
                            record.setDocumentId(documentId);
                            record.setUserId(currentUser.userId());
                            record.setQuestion(question);
                            record.setAnswer(modelResponse.answer());
                            record.setModelName(modelResponse.modelName());
                            record.setPromptTokens(modelResponse.promptTokens());
                            record.setCompletionTokens(modelResponse.completionTokens());
                            record.setStatus("SUCCESS");
                            record.setCostTimeMs(costTimeMs);
                            if (!save(record)) {
                                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存问答记录失败");
                            }
                            List<AiQaReference> references =
                                    chunks.stream()
                                            .map(
                                                    candidate -> {
                                                        AiQaReference reference =
                                                                new AiQaReference();
                                                        reference.setQaRecordId(record.getId());
                                                        reference.setChunkId(
                                                                candidate.chunk().getId());
                                                        reference.setRelevanceScore(
                                                                candidate.score());
                                                        return reference;
                                                    })
                                            .toList();
                            if (!referenceService.saveBatch(references)) {
                                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存问答引用失败");
                            }
                            return record;
                        });
        if (saved == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存问答记录失败");
        }
        return saved;
    }

    private void saveFailed(
            Long ticketId,
            Long documentId,
            String question,
            long costTimeMs,
            RuntimeException exception) {
        try {
            transactionTemplate.executeWithoutResult(
                    status -> {
                        AiChatLog record = new AiChatLog();
                        record.setTicketId(ticketId);
                        record.setDocumentId(documentId);
                        record.setUserId(currentUser.userId());
                        record.setQuestion(question);
                        record.setStatus("FAILED");
                        record.setErrorMessage(conciseError(exception));
                        record.setCostTimeMs(costTimeMs);
                        if (!save(record)) {
                            throw new IllegalStateException("保存失败问答记录失败");
                        }
                    });
        } catch (RuntimeException saveException) {
            log.error("保存失败问答记录失败，ticketId={}", ticketId, saveException);
        }
    }

    private List<AiReferenceVO> loadReferences(Long recordId) {
        List<AiQaReference> references =
                referenceService.list(
                        new LambdaQueryWrapper<AiQaReference>()
                                .eq(AiQaReference::getQaRecordId, recordId)
                                .orderByDesc(AiQaReference::getRelevanceScore));
        if (references.isEmpty()) {
            return List.of();
        }
        Map<Long, DocumentChunk> chunks =
                chunkService
                        .listByIds(references.stream().map(AiQaReference::getChunkId).toList())
                        .stream()
                        .collect(Collectors.toMap(DocumentChunk::getId, Function.identity()));
        return references.stream()
                .filter(reference -> chunks.containsKey(reference.getChunkId()))
                .map(
                        reference ->
                                toReference(
                                        chunks.get(reference.getChunkId()),
                                        reference.getRelevanceScore()))
                .toList();
    }

    private AiChatVO toChatVO(AiChatLog record, List<ScoredChunk> chunks) {
        AiChatVO result = new AiChatVO();
        result.setQuestionId(record.getId());
        result.setTicketId(record.getTicketId());
        result.setDocumentId(record.getDocumentId());
        result.setAnswer(record.getAnswer());
        result.setModelName(record.getModelName());
        result.setCostTimeMs(record.getCostTimeMs());
        result.setReferences(
                chunks.stream()
                        .map(candidate -> toReference(candidate.chunk(), candidate.score()))
                        .toList());
        return result;
    }

    private AiChatLogVO toLogVO(AiChatLog record, List<AiReferenceVO> references) {
        AiChatLogVO result = new AiChatLogVO();
        result.setId(record.getId());
        result.setTicketId(record.getTicketId());
        result.setDocumentId(record.getDocumentId());
        result.setUserId(record.getUserId());
        result.setQuestion(record.getQuestion());
        result.setAnswer(record.getAnswer());
        result.setModelName(record.getModelName());
        result.setPromptTokens(record.getPromptTokens());
        result.setCompletionTokens(record.getCompletionTokens());
        result.setStatus(record.getStatus());
        result.setErrorMessage(record.getErrorMessage());
        result.setCostTimeMs(record.getCostTimeMs());
        result.setReferences(references);
        result.setCreateTime(record.getCreateTime());
        return result;
    }

    private AiReferenceVO toReference(DocumentChunk chunk, Double score) {
        AiReferenceVO reference = new AiReferenceVO();
        reference.setChunkId(chunk.getId());
        reference.setDocumentId(chunk.getDocumentId());
        reference.setChunkIndex(chunk.getChunkIndex());
        reference.setPageNumber(chunk.getPageNumber());
        reference.setRelevanceScore(score);
        String content = chunk.getContent();
        reference.setExcerpt(content.substring(0, Math.min(content.length(), 300)));
        return reference;
    }

    private String conciseError(RuntimeException exception) {
        String message =
                StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "未知模型调用错误";
        return message.substring(0, Math.min(message.length(), 512));
    }

    private void validatePage(Long pageNum, Long pageSize) {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageNum 必须大于 0，pageSize 必须在 1 到 100 之间");
        }
    }
}
