package com.opsagent.knowledge;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * 提供按版本、按权限核验的真实正文分页；读取前后均检查归属与体验租期。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class KnowledgeDocumentContentService {
    static final int PAGE_SIZE = 20_000;
    private final KnowledgeService knowledge;
    private final FileStorageService storage;
    private final DocumentBodyReader reader;
    private final Path storageRoot;
    private final long maximumFileBytes;
    private final Semaphore readers = new Semaphore(2);

    KnowledgeDocumentContentService(
            KnowledgeService knowledge,
            FileStorageService storage,
            DocumentBodyReader reader,
            KnowledgeProperties properties) {
        this.knowledge = knowledge;
        this.storage = storage;
        this.reader = reader;
        this.storageRoot = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
        this.maximumFileBytes = properties.getMaxFileSizeBytes();
    }

    Content page(long id, int page, Integer expectedVersion) {
        Map<String, Object> document = knowledge.readableDocument(id);
        int version = ((Number) document.get("version")).intValue();
        if (expectedVersion != null && expectedVersion != version)
            throw new BusinessException(ErrorCode.CONFLICT, "文档版本已变化，请刷新详情后重新阅读");
        if (page < 1) throw new BusinessException(ErrorCode.VALIDATION, "正文页码无效");
        if (!readers.tryAcquire())
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "正文读取繁忙，请稍后重试");
        try {
            Content result = read(id, document, version, page);
            Map<String, Object> current = knowledge.readableDocument(id);
            if (((Number) current.get("version")).intValue() != version
                    || !Objects.equals(current.get("storage_path"), document.get("storage_path")))
                throw new BusinessException(ErrorCode.CONFLICT, "文档在读取期间已更新，请刷新后重试");
            return result;
        } finally {
            readers.release();
        }
    }

    private Content read(long id, Map<String, Object> document, int version, int page) {
        String type = Objects.toString(document.get("file_type"), "").toLowerCase(Locale.ROOT);
        boolean originalText = type.equals("md") || type.equals("markdown") || type.equals("txt");
        String format = type.equals("md") || type.equals("markdown") ? "MARKDOWN" : "TEXT";
        String source = originalText ? "ORIGINAL_FILE_TEXT" : "EXTRACTED_FILE_TEXT";
        String content;
        try {
            String relative = Objects.toString(document.get("storage_path"), "");
            if (relative.isBlank())
                return unavailable(id, version, "SOURCE_MISSING", "原文件缺失，无法显示真实正文；已保留的切片仍可查看。");
            Path realRoot = storageRoot.toRealPath();
            Path file = storage.resolve(relative).toRealPath();
            if (!file.startsWith(realRoot) || !Files.isRegularFile(file) || !Files.isReadable(file))
                return unavailable(id, version, "SOURCE_MISSING", "原文件不可读取，无法显示真实正文；可继续查看已有切片。");
            if (Files.size(file) > maximumFileBytes)
                return unavailable(id, version, "TOO_LARGE", "原文件超过在线正文读取上限，请使用已有切片阅读。");
            content = reader.read(file, type);
        } catch (DocumentBodyReader.ContentLimitException exception) {
            return unavailable(id, version, "TOO_LARGE", "正文过长或提取耗时超过上限，未展示截断正文；请使用已有切片阅读。");
        } catch (java.nio.file.NoSuchFileException exception) {
            return unavailable(id, version, "SOURCE_MISSING", "原文件缺失，无法显示真实正文；已保留的切片仍可查看。");
        } catch (IOException | RuntimeException exception) {
            return unavailable(
                    id,
                    version,
                    "UNREADABLE",
                    "原文件正文暂不可读取；TXT/Markdown 需 UTF-8，PDF/DOCX 需可提取文字。请核对原文件后重试。");
        }
        if (content.isBlank())
            return unavailable(id, version, "NO_TEXT", "原文件没有可提取正文；扫描 PDF 或图片暂无 OCR 内容，可查看已有切片核对。");
        int pages = (content.length() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page > pages) throw new BusinessException(ErrorCode.VALIDATION, "正文页码超过范围，请返回上一页");
        int start = boundary(content, (page - 1) * PAGE_SIZE);
        int end = boundary(content, Math.min(page * PAGE_SIZE, content.length()));
        return new Content(
                id,
                version,
                "AVAILABLE",
                source,
                format,
                content.substring(start, end),
                page,
                pages,
                content.length(),
                originalText ? "直接读取上传原文件，未拼接检索切片。" : "从原 PDF/DOCX 提取正文文字，未保留图片和原始版式，未拼接检索切片。");
    }

    private static int boundary(String text, int index) {
        return index > 0
                        && index < text.length()
                        && Character.isLowSurrogate(text.charAt(index))
                        && Character.isHighSurrogate(text.charAt(index - 1))
                ? index - 1
                : index;
    }

    private Content unavailable(long id, int version, String status, String notice) {
        return new Content(id, version, status, "UNAVAILABLE", "TEXT", "", 1, 0, 0, notice);
    }

    record Content(
            long documentId,
            int version,
            String status,
            String source,
            String format,
            String text,
            int pageNum,
            int totalPages,
            int totalCharacters,
            String notice) {}
}
