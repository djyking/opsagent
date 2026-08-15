package com.example.opsagent.document.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.common.exception.BusinessException;
import com.example.opsagent.common.exception.ErrorCode;
import com.example.opsagent.document.config.DocumentStorageProperties;
import com.example.opsagent.document.dto.DocumentQueryRequest;
import com.example.opsagent.document.entity.Document;
import com.example.opsagent.document.entity.DocumentChunk;
import com.example.opsagent.document.dao.DocumentDao;
import com.example.opsagent.document.parser.DocumentParser;
import com.example.opsagent.document.service.DocumentChunkService;
import com.example.opsagent.document.service.DocumentService;
import com.example.opsagent.document.vo.DocumentChunkVO;
import com.example.opsagent.document.vo.DocumentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 实现文档安全上传、本地存储、解析、切片和元数据查询。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl extends ServiceImpl<DocumentDao, Document> implements DocumentService {

    private static final int CHUNK_SIZE = 1000;

    private static final Set<String> DOCUMENT_STATUSES = Set.of("UPLOADED", "PARSED", "FAILED");

    private final DocumentChunkService chunkService;

    private final List<DocumentParser> parsers;

    private final DocumentStorageProperties storageProperties;

    private final TransactionTemplate transactionTemplate;

    @Override
    public DocumentVO upload(MultipartFile file, String uploader) {
        validateUpload(file, uploader);
        String originalFilename = file.getOriginalFilename();
        String extension = extensionOf(originalFilename);
        if (parsers.stream().noneMatch(parser -> parser.supports(extension))) {
            throw new IllegalArgumentException("仅支持 txt、md 和 markdown 文档");
        }

        Path storageRoot = storageRoot();
        LocalDate today = LocalDate.now();
        Path directory = storageRoot.resolve(String.valueOf(today.getYear()))
            .resolve(String.format("%02d", today.getMonthValue())).normalize();
        Path target = directory.resolve(UUID.randomUUID() + "." + extension).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("文件存储路径不合法");
        }

        try {
            Files.createDirectories(directory);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }

            Document document = new Document();
            document.setFileName(originalFilename);
            document.setFileType(extension);
            document.setFileSize(file.getSize());
            document.setStoragePath(target.toString());
            document.setStatus("UPLOADED");
            document.setUploader(uploader.trim());
            document.setDeleted(0);
            transactionTemplate.executeWithoutResult(status -> {
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
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文档上传失败");
        }
    }

    @Override
    public DocumentVO detail(Long id) {
        return toVO(requireDocument(id));
    }

    @Override
    public PageResponse<DocumentVO> pageDocuments(DocumentQueryRequest request) {
        validatePage(request.getPageNum(), request.getPageSize());
        LambdaQueryWrapper<Document> query = new LambdaQueryWrapper<>();
        query.like(StringUtils.hasText(request.getFileName()), Document::getFileName,
                StringUtils.hasText(request.getFileName()) ? request.getFileName().trim() : null)
            .eq(StringUtils.hasText(request.getUploader()), Document::getUploader,
                StringUtils.hasText(request.getUploader()) ? request.getUploader().trim() : null);
        if (StringUtils.hasText(request.getStatus())) {
            String status = request.getStatus().trim().toUpperCase(Locale.ROOT);
            if (!DOCUMENT_STATUSES.contains(status)) {
                throw new IllegalArgumentException("文档状态只能是 UPLOADED、PARSED 或 FAILED");
            }
            query.eq(Document::getStatus, status);
        }
        query.orderByDesc(Document::getCreateTime);
        Page<Document> page = page(new Page<>(request.getPageNum(), request.getPageSize()), query);
        return PageResponse.from(page, this::toVO);
    }

    @Override
    public DocumentVO parseDocument(Long id) {
        Document document = requireDocument(id);
        DocumentParser parser = parsers.stream()
            .filter(candidate -> candidate.supports(document.getFileType()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("不支持的文档类型：" + document.getFileType()));

        try {
            String text = parser.parse(Path.of(document.getStoragePath()));
            List<String> contents = splitText(text);
            transactionTemplate.executeWithoutResult(status -> {
                chunkService.remove(new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, id));
                List<DocumentChunk> chunks = new ArrayList<>(contents.size());
                for (int index = 0; index < contents.size(); index++) {
                    DocumentChunk chunk = new DocumentChunk();
                    chunk.setDocumentId(id);
                    chunk.setChunkIndex(index);
                    chunk.setContent(contents.get(index));
                    chunk.setTokenEstimate(Math.max(1, contents.get(index).length() / 4));
                    chunk.setDeleted(0);
                    chunks.add(chunk);
                }
                if (!chunkService.saveBatch(chunks)) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存文档切片失败");
                }
                document.setStatus("PARSED");
                if (!updateById(document)) {
                    throw new BusinessException(ErrorCode.CONFLICT, "更新文档解析状态失败");
                }
            });
            return toVO(requireDocument(id));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> {
                document.setStatus("FAILED");
                if (!updateById(document)) {
                    log.error("更新文档失败状态失败，documentId={}", id);
                }
            });
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档解析失败：" + exception.getMessage());
        }
    }

    @Override
    public List<DocumentChunkVO> listChunks(Long id) {
        requireDocument(id);
        return chunkService.list(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, id)
                .orderByAsc(DocumentChunk::getChunkIndex))
            .stream()
            .map(this::toChunkVO)
            .toList();
    }

    @Override
    public void deleteDocument(Long id) {
        Document document = requireDocument(id);
        transactionTemplate.executeWithoutResult(status -> {
            chunkService.remove(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, id));
            if (!removeById(id)) {
                throw new BusinessException(ErrorCode.CONFLICT, "删除文档失败");
            }
        });
        deleteFileQuietly(Path.of(document.getStoragePath()));
    }

    private void validateUpload(MultipartFile file, String uploader) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (!StringUtils.hasText(uploader)) {
            throw new IllegalArgumentException("上传人不能为空");
        }
        if (file.getSize() > storageProperties.getMaxFileSize()) {
            throw new IllegalArgumentException("文件大小不能超过 " + storageProperties.getMaxFileSize() + " 字节");
        }
        if (!StringUtils.hasText(file.getOriginalFilename()) || file.getOriginalFilename().length() > 255) {
            throw new IllegalArgumentException("文件名不能为空且长度不能超过 255 个字符");
        }
    }

    private String extensionOf(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new IllegalArgumentException("文件必须包含受支持的扩展名");
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private Path storageRoot() {
        if (!StringUtils.hasText(storageProperties.getStorageRoot())) {
            throw new IllegalStateException("文档存储目录不能为空");
        }
        return Path.of(storageProperties.getStorageRoot()).toAbsolutePath().normalize();
    }

    private Document requireDocument(Long id) {
        Document document = getById(id);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        return document;
    }

    private List<String> splitText(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("文档没有可解析的文本内容");
        }
        String normalized = text.replace("\r\n", "\n").trim();
        List<String> chunks = new ArrayList<>();
        for (int offset = 0; offset < normalized.length(); offset += CHUNK_SIZE) {
            chunks.add(normalized.substring(offset, Math.min(offset + CHUNK_SIZE, normalized.length())));
        }
        return chunks;
    }

    private DocumentVO toVO(Document document) {
        DocumentVO result = new DocumentVO();
        result.setId(document.getId());
        result.setFileName(document.getFileName());
        result.setFileType(document.getFileType());
        result.setFileSize(document.getFileSize());
        result.setStatus(document.getStatus());
        result.setUploader(document.getUploader());
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
        result.setTokenEstimate(chunk.getTokenEstimate());
        result.setCreateTime(chunk.getCreateTime());
        return result;
    }

    private void deleteFileQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("删除本地文档失败，path={}", path, exception);
        }
    }

    private void validatePage(Long pageNum, Long pageSize) {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageNum 必须大于 0，pageSize 必须在 1 到 100 之间");
        }
    }
}
