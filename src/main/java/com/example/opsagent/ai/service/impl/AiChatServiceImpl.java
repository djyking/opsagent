package com.example.opsagent.ai.service.impl;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.ai.client.AiModelClient;
import com.example.opsagent.ai.dto.AiChatLogQueryRequest;
import com.example.opsagent.ai.dto.AiChatRequest;
import com.example.opsagent.ai.entity.AiChatLog;
import com.example.opsagent.ai.dao.AiChatLogDao;
import com.example.opsagent.ai.service.AiChatService;
import com.example.opsagent.ai.vo.AiChatLogVO;
import com.example.opsagent.ai.vo.AiChatVO;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.common.exception.BusinessException;
import com.example.opsagent.common.exception.ErrorCode;
import com.example.opsagent.document.entity.DocumentChunk;
import com.example.opsagent.document.service.DocumentChunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 实现基于文档切片的简化问答及问答日志查询。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl extends ServiceImpl<AiChatLogDao, AiChatLog> implements AiChatService {

    private final DocumentChunkService chunkService;

    private final AiModelClient aiModelClient;

    @Override
    public AiChatVO chat(AiChatRequest request) {
        int topN = request.getTopN() == null ? 3 : request.getTopN();
        if (topN < 1 || topN > 10) {
            throw new IllegalArgumentException("topN 必须在 1 到 10 之间");
        }
        LambdaQueryWrapper<DocumentChunk> query = new LambdaQueryWrapper<DocumentChunk>()
            .eq(request.getDocumentId() != null, DocumentChunk::getDocumentId, request.getDocumentId())
            .orderByAsc(DocumentChunk::getDocumentId)
            .orderByAsc(DocumentChunk::getChunkIndex);
        List<DocumentChunk> chunks = chunkService.page(new Page<>(1, topN), query).getRecords();
        if (chunks.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "没有可用于问答的已解析文档片段");
        }

        String context = chunks.stream()
            .map(chunk -> "[chunk-" + chunk.getId() + "] " + chunk.getContent())
            .collect(java.util.stream.Collectors.joining("\n"));
        String prompt = "请仅根据以下运维文档内容回答问题。\n\n" + context
            + "\n\n问题：" + request.getQuestion().trim();
        long start = System.nanoTime();
        String answer = aiModelClient.chat(prompt);
        long costTimeMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String usedChunks = chunks.stream().map(DocumentChunk::getId).map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        AiChatLog log = new AiChatLog();
        log.setQuestion(request.getQuestion().trim());
        log.setAnswer(answer);
        log.setDocumentId(request.getDocumentId());
        log.setUsedChunks(usedChunks);
        log.setCostTimeMs(costTimeMs);
        if (!save(log)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存问答日志失败");
        }

        AiChatVO result = new AiChatVO();
        result.setAnswer(answer);
        result.setDocumentId(request.getDocumentId());
        result.setUsedChunks(usedChunks);
        result.setCostTimeMs(costTimeMs);
        return result;
    }

    @Override
    public PageResponse<AiChatLogVO> pageLogs(AiChatLogQueryRequest request) {
        if (request.getPageNum() == null || request.getPageNum() < 1 || request.getPageSize() == null
            || request.getPageSize() < 1 || request.getPageSize() > 100) {
            throw new IllegalArgumentException("pageNum 必须大于 0，pageSize 必须在 1 到 100 之间");
        }
        LambdaQueryWrapper<AiChatLog> query = new LambdaQueryWrapper<AiChatLog>()
            .eq(request.getDocumentId() != null, AiChatLog::getDocumentId, request.getDocumentId())
            .orderByDesc(AiChatLog::getCreateTime);
        Page<AiChatLog> page = page(new Page<>(request.getPageNum(), request.getPageSize()), query);
        return PageResponse.from(page, this::toVO);
    }

    @Override
    public AiChatLogVO logDetail(Long id) {
        AiChatLog log = getById(id);
        if (log == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "问答日志不存在");
        }
        return toVO(log);
    }

    private AiChatLogVO toVO(AiChatLog log) {
        AiChatLogVO result = new AiChatLogVO();
        result.setId(log.getId());
        result.setQuestion(log.getQuestion());
        result.setAnswer(log.getAnswer());
        result.setDocumentId(log.getDocumentId());
        result.setUsedChunks(log.getUsedChunks());
        result.setCostTimeMs(log.getCostTimeMs());
        result.setCreateTime(log.getCreateTime());
        return result;
    }
}
