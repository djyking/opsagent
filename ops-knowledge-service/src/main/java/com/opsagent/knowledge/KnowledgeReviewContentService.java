package com.opsagent.knowledge;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 审核人专用内容读取，复用已有解析切片与原文件，不改变文档发布状态。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class KnowledgeReviewContentService {
    private final KnowledgeService knowledge;
    private final KnowledgeRepository repository;
    private final FileStorageService storage;
    private final Path storageRoot;

    KnowledgeReviewContentService(KnowledgeService knowledge, KnowledgeRepository repository,
            FileStorageService storage, KnowledgeProperties properties) {
        this.knowledge = knowledge;
        this.repository = repository;
        this.storage = storage;
        this.storageRoot = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    Preview preview(long id, int requestedPage, int pageSize) {
        Map<String, Object> document = knowledge.reviewDocument(id);
        long total = repository.reviewChunkCount(id);
        int page = (int) Math.min(requestedPage, Math.max(1, (total + pageSize - 1) / pageSize));
        return new Preview(id, text(document, "original_name"), text(document, "file_type"),
                number(document, "file_size"), (int) number(document, "version"),
                text(document, "status"), text(document, "review_status"), text(document, "parse_error"),
                sourceAvailable(document), total, page, pageSize,
                repository.reviewChunks(id, (page - 1) * pageSize, pageSize));
    }

    @Transactional(readOnly = true)
    ParsedText text(long id) {
        Map<String, Object> document = knowledge.reviewDocument(id);
        long total = repository.reviewChunkCount(id);
        if (total == 0) throw new BusinessException(ErrorCode.CONFLICT, "尚未生成可读解析正文，请检查解析状态");
        if (total > 5000 || repository.reviewTextLength(id) > 2_000_000) {
            throw new BusinessException(ErrorCode.VALIDATION, "解析正文较长，请使用分页切片阅读或下载原文件");
        }
        String content = repository.reviewChunks(id, 0, (int) total).stream()
                .map(Chunk::content).collect(java.util.stream.Collectors.joining("\n\n"));
        return new ParsedText(id, (int) number(document, "version"), total, content);
    }

    Source source(long id) {
        Map<String, Object> document = knowledge.reviewDocument(id);
        Path file = sourcePath(document);
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "原文件暂不可用，已解析内容仍可在预览中核对");
        }
        return new Source(text(document, "original_name"), file);
    }

    private boolean sourceAvailable(Map<String, Object> document) {
        Path file = sourcePath(document);
        return file != null && Files.isRegularFile(file) && Files.isReadable(file);
    }

    private Path sourcePath(Map<String, Object> document) {
        String relative = text(document, "storage_path");
        if (relative.isBlank()) return null;
        try {
            Path realRoot = storageRoot.toRealPath();
            Path realFile = storage.resolve(relative).toRealPath();
            return realFile.startsWith(realRoot) ? realFile : null;
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            return null;
        }
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }

    private static long number(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number value ? value.longValue() : 0;
    }

    /** @author heyu */
    record Chunk(long id, int chunkIndex, String content, Integer tokenCount, Integer pageNumber) {}

    /** @author heyu */
    record Preview(long documentId, String originalName, String fileType, long fileSize, int version,
                   String parseStatus, String reviewStatus, String parseError, boolean sourceAvailable,
                   long total, int pageNum, int pageSize, List<Chunk> chunks) {}

    /** @author heyu */
    record ParsedText(long documentId, int version, long chunkCount, String text) {}

    /** @author heyu */
    record Source(String originalName, Path path) {}
}
