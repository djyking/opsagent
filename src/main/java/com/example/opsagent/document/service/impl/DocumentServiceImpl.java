package com.example.opsagent.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.common.exception.BusinessException;
import com.example.opsagent.common.exception.ErrorCode;
import com.example.opsagent.document.config.DocumentStorageProperties;
import com.example.opsagent.document.dao.DocumentDao;
import com.example.opsagent.document.entity.Document;
import com.example.opsagent.document.entity.DocumentChunk;
import com.example.opsagent.document.enums.DocumentParseStatus;
import com.example.opsagent.document.parser.DocumentParser;
import com.example.opsagent.document.service.DocumentChunkService;
import com.example.opsagent.document.service.DocumentService;
import com.example.opsagent.document.vo.DocumentChunkVO;
import com.example.opsagent.document.vo.DocumentVO;
import com.example.opsagent.security.current.CurrentUserContext;
import com.example.opsagent.ticket.service.TicketService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 实现工单文档安全上传、本地存储补偿、非长事务解析和可引用切片。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl extends ServiceImpl<DocumentDao, Document>
        implements DocumentService {

    private static final Map<String, Set<String>> ALLOWED_CONTENT_TYPES =
            Map.of(
                    "txt", Set.of("text/plain"),
                    "md", Set.of("text/plain", "text/markdown", "text/x-markdown"),
                    "markdown", Set.of("text/plain", "text/markdown", "text/x-markdown"),
                    "pdf", Set.of("application/pdf"),
                    "docx",
                            Set.of(
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/x-tika-ooxml"));

    private final DocumentChunkService chunkService;
    private final List<DocumentParser> parsers;
    private final DocumentStorageProperties storageProperties;
    private final TransactionTemplate transactionTemplate;
    private final TicketService ticketService;
    private final CurrentUserContext currentUser;

    @Override
    public DocumentVO upload(Long ticketId, MultipartFile file) {
        ticketService.requireDocumentPermission(ticketId);
        validateUpload(file);
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        requireSupportedParser(extension);
        LocalDate today = LocalDate.now();
        String storageName = UUID.randomUUID() + "." + extension;
        Path relativePath =
                Path.of(
                        String.valueOf(today.getYear()),
                        String.format("%02d", today.getMonthValue()),
                        storageName);
        Path target = resolveStoragePath(relativePath.toString());
        try {
            String detectedContentType = detectContentType(file, originalName);
            validateContentType(extension, file.getContentType(), detectedContentType);
            Files.createDirectories(target.getParent());
            String fileHash = copyAndHash(file, target);
            Document document = new Document();
            document.setTicketId(ticketId);
            document.setOriginalName(originalName);
            document.setStorageName(storageName);
            document.setStoragePath(relativePath.toString().replace('\\', '/'));
            document.setContentType(detectedContentType);
            document.setFileExtension(extension);
            document.setFileSize(file.getSize());
            document.setFileHash(fileHash);
            document.setParseStatus(DocumentParseStatus.PENDING.name());
            document.setCreateBy(currentUser.userId());
            document.setDeleted(0);
            transactionTemplate.executeWithoutResult(
                    status -> {
                        if (!save(document)) {
                            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存文档元数据失败");
                        }
                    });
            return toVO(requireDocument(document.getId()));
        } catch (RuntimeException | IOException exception) {
            deleteFileQuietly(target);
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            log.error("文档上传失败，ticketId={}", ticketId, exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文档上传失败");
        }
    }

    @Override
    public List<DocumentVO> listByTicket(Long ticketId) {
        ticketService.requireAccessibleTicket(ticketId);
        return list(
                        new LambdaQueryWrapper<Document>()
                                .eq(Document::getTicketId, ticketId)
                                .orderByDesc(Document::getCreateTime))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public DocumentVO detail(Long id) {
        return toVO(requireAccessibleDocument(id));
    }

    @Override
    public DocumentVO parseDocument(Long id) {
        Document accessible = requireAccessibleDocument(id);
        ticketService.requireDocumentPermission(accessible.getTicketId());
        markParsing(id);
        Document document = requireDocument(id);
        try {
            DocumentParser parser = requireSupportedParser(document.getFileExtension());
            String text = cleanText(parser.parse(resolveStoragePath(document.getStoragePath())));
            markSuccess(id, splitText(text));
            return toVO(requireDocument(id));
        } catch (RuntimeException | IOException exception) {
            markFailed(id, conciseError(exception));
            log.error("文档解析失败，documentId={}", id, exception);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档解析失败");
        }
    }

    @Override
    public List<DocumentChunkVO> listChunks(Long id) {
        requireAccessibleDocument(id);
        return chunkService
                .list(
                        new LambdaQueryWrapper<DocumentChunk>()
                                .eq(DocumentChunk::getDocumentId, id)
                                .orderByAsc(DocumentChunk::getChunkIndex))
                .stream()
                .map(this::toChunkVO)
                .toList();
    }

    @Override
    public Document requireAccessibleDocument(Long id) {
        Document document = requireDocument(id);
        ticketService.requireAccessibleTicket(document.getTicketId());
        return document;
    }

    @Override
    public void deleteDocument(Long id) {
        Document document = requireAccessibleDocument(id);
        if (!currentUser.hasRole("ADMIN") && !currentUser.userId().equals(document.getCreateBy())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权删除该文档");
        }
        transactionTemplate.executeWithoutResult(
                status -> {
                    chunkService.remove(
                            new LambdaQueryWrapper<DocumentChunk>()
                                    .eq(DocumentChunk::getDocumentId, id));
                    if (!removeById(id)) {
                        throw new BusinessException(ErrorCode.CONFLICT, "删除文档失败");
                    }
                });
        deleteFileQuietly(resolveStoragePath(document.getStoragePath()));
    }

    private void markParsing(Long id) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    requireDocument(id);
                    boolean updated =
                            update(
                                    new LambdaUpdateWrapper<Document>()
                                            .eq(Document::getId, id)
                                            .ne(
                                                    Document::getParseStatus,
                                                    DocumentParseStatus.PARSING.name())
                                            .set(
                                                    Document::getParseStatus,
                                                    DocumentParseStatus.PARSING.name())
                                            .set(Document::getParseError, null));
                    if (!updated) {
                        throw new BusinessException(ErrorCode.CONFLICT, "文档正在解析或状态已变化");
                    }
                });
    }

    private void markSuccess(Long id, List<String> contents) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    Document document = requireDocument(id);
                    chunkService.remove(
                            new LambdaQueryWrapper<DocumentChunk>()
                                    .eq(DocumentChunk::getDocumentId, id));
                    List<DocumentChunk> chunks = new ArrayList<>(contents.size());
                    for (int index = 0; index < contents.size(); index++) {
                        DocumentChunk chunk = new DocumentChunk();
                        chunk.setDocumentId(id);
                        chunk.setChunkIndex(index);
                        chunk.setContent(contents.get(index));
                        chunk.setTokenCount(Math.max(1, contents.get(index).length() / 4));
                        chunk.setMetadataJson(
                                "{\"source\":\"" + escapeJson(document.getOriginalName()) + "\"}");
                        chunk.setDeleted(0);
                        chunks.add(chunk);
                    }
                    if (!chunkService.saveBatch(chunks)) {
                        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存文档切片失败");
                    }
                    boolean updated =
                            update(
                                    new LambdaUpdateWrapper<Document>()
                                            .eq(Document::getId, id)
                                            .eq(
                                                    Document::getParseStatus,
                                                    DocumentParseStatus.PARSING.name())
                                            .set(
                                                    Document::getParseStatus,
                                                    DocumentParseStatus.SUCCESS.name())
                                            .set(Document::getParseError, null));
                    if (!updated) {
                        throw new BusinessException(ErrorCode.CONFLICT, "更新文档解析结果失败");
                    }
                });
    }

    private void markFailed(Long id, String error) {
        try {
            transactionTemplate.executeWithoutResult(
                    status -> {
                        Document document = requireDocument(id);
                        document.setParseStatus(DocumentParseStatus.FAILED.name());
                        document.setParseError(error);
                        if (!updateById(document)) {
                            throw new IllegalStateException("更新文档失败状态失败");
                        }
                    });
        } catch (RuntimeException statusException) {
            log.error("保存文档解析失败状态失败，documentId={}", id, statusException);
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > storageProperties.getMaxFileSize()) {
            throw new IllegalArgumentException(
                    "文件大小不能超过 " + storageProperties.getMaxFileSize() + " 字节");
        }
        if (!StringUtils.hasText(file.getOriginalFilename())) {
            throw new IllegalArgumentException("文件名不能为空且长度不能超过 255 个字符");
        }
        safeOriginalName(file.getOriginalFilename());
    }

    private String extensionOf(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new IllegalArgumentException("文件必须包含受支持的扩展名");
        }
        String extension = filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.containsKey(extension)) {
            throw new IllegalArgumentException("仅支持 PDF、DOCX、TXT 和 Markdown 文档");
        }
        return extension;
    }

    private String safeOriginalName(String filename) {
        String normalized = filename.replace('\\', '/');
        String safeName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (!StringUtils.hasText(safeName)
                || safeName.length() > 255
                || ".".equals(safeName)
                || "..".equals(safeName)) {
            throw new IllegalArgumentException("文件名不能为空且长度不能超过 255 个字符");
        }
        if (safeName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("文件名不能包含控制字符");
        }
        return safeName;
    }

    private DocumentParser requireSupportedParser(String extension) {
        return parsers.stream()
                .filter(parser -> parser.supports(extension))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的文档类型：" + extension));
    }

    private String detectContentType(MultipartFile file, String originalName) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return new Tika().detect(inputStream, originalName);
        }
    }

    private void validateContentType(String extension, String declaredType, String detectedType) {
        Set<String> allowed = ALLOWED_CONTENT_TYPES.get(extension);
        if (!allowed.contains(detectedType)) {
            throw new IllegalArgumentException("文件内容与扩展名不匹配");
        }
        if (StringUtils.hasText(declaredType)
                && !"application/octet-stream".equalsIgnoreCase(declaredType)
                && !allowed.contains(declaredType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("文件 Content-Type 与扩展名不匹配");
        }
    }

    private String copyAndHash(MultipartFile file, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
        try (InputStream inputStream = file.getInputStream();
                DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
            Files.copy(digestInputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path storageRoot() {
        if (!StringUtils.hasText(storageProperties.getStorageRoot())) {
            throw new IllegalStateException("文档存储目录不能为空");
        }
        return Path.of(storageProperties.getStorageRoot()).toAbsolutePath().normalize();
    }

    private Path resolveStoragePath(String relativePath) {
        Path root = storageRoot();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("文件存储路径不合法");
        }
        return resolved;
    }

    private Document requireDocument(Long id) {
        Document document = getById(id);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        return document;
    }

    private String cleanText(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("文档没有可解析的文本内容，扫描 PDF 第一阶段不支持 OCR");
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder cleaned = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == '\n' || character == '\t' || !Character.isISOControl(character)) {
                cleaned.append(character);
            }
        }
        return cleaned.toString()
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private List<String> splitText(String text) {
        int chunkSize = storageProperties.getChunkSize();
        int overlap = storageProperties.getChunkOverlap();
        if (chunkSize < 200 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalStateException("文档切片参数不合法");
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }

    private DocumentVO toVO(Document document) {
        DocumentVO result = new DocumentVO();
        result.setId(document.getId());
        result.setTicketId(document.getTicketId());
        result.setOriginalName(document.getOriginalName());
        result.setContentType(document.getContentType());
        result.setFileExtension(document.getFileExtension());
        result.setFileSize(document.getFileSize());
        result.setFileHash(document.getFileHash());
        result.setParseStatus(document.getParseStatus());
        result.setParseError(document.getParseError());
        result.setCreateBy(document.getCreateBy());
        result.setCreateTime(document.getCreateTime());
        result.setUpdateTime(document.getUpdateTime());
        return result;
    }

    private DocumentChunkVO toChunkVO(DocumentChunk chunk) {
        DocumentChunkVO result = new DocumentChunkVO();
        result.setId(chunk.getId());
        result.setDocumentId(chunk.getDocumentId());
        result.setChunkIndex(chunk.getChunkIndex());
        result.setContent(chunk.getContent());
        result.setTokenCount(chunk.getTokenCount());
        result.setPageNumber(chunk.getPageNumber());
        result.setSectionTitle(chunk.getSectionTitle());
        result.setCreateTime(chunk.getCreateTime());
        return result;
    }

    private String conciseError(Exception exception) {
        String message =
                StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "未知解析错误";
        return message.substring(0, Math.min(message.length(), 512));
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void deleteFileQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("删除本地文档失败，path={}", path, exception);
        }
    }
}
