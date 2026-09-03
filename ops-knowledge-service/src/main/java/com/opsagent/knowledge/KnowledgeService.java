package com.opsagent.knowledge;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库领域服务，编排文件存储、文档解析和本地检索降级。
 *
 * @author heyu
 * @since 2026/8/20
 */
@Service
public class KnowledgeService {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeService.class);
    private static final Pattern LATIN_TERM = Pattern.compile("[A-Za-z0-9_]{2,}");
    private final KnowledgeRepository repo;
    private final FileStorageService storage;
    private final DocumentParserService parser;
    private final DocumentParsePublisher publisher;
    private final KnowledgeIndexService indexService;
    private final KnowledgeIndexCompensationService compensationService;

    KnowledgeService(
            KnowledgeRepository repo,
            FileStorageService storage,
            DocumentParserService parser,
            DocumentParsePublisher publisher,
            KnowledgeIndexService indexService,
            KnowledgeIndexCompensationService compensationService) {
        this.repo = repo;
        this.storage = storage;
        this.parser = parser;
        this.publisher = publisher;
        this.indexService = indexService;
        this.compensationService = compensationService;
    }

    long createBase(String name, String description) {
        return repo.createBase(name.trim(), description, SecurityUsers.current().userId());
    }

    List<Map<String, Object>> bases() {
        return repo.bases();
    }

    List<Map<String, Object>> documents(long base) {
        return repo.documents(base);
    }

    List<Map<String, Object>> ticketDocuments(long ticketId) {
        var principal = SecurityUsers.current();
        return repo.ticketDocuments(
                ticketId, principal.userId(), administrator(principal.roles()));
    }

    long upload(long base, Long ticketId, MultipartFile file, String requestedVisibility) {
        try {
            var principal = SecurityUsers.current();
            String visibility = normalizeVisibility(requestedVisibility, principal.roles());
            return repo.addDocument(
                    base, ticketId, storage.store(file), principal.userId(), visibility);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败：" + e.getMessage());
        }
    }

    long requestParse(long id) {
        Map<String, Object> document = repo.document(id);
        if (document == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        long taskId = repo.createParseTask(id);
        publisher.publish(id, taskId);
        return taskId;
    }

    Map<String, Object> parseTask(long taskId) {
        Map<String, Object> task = repo.parseTask(taskId);
        if (task == null) throw new BusinessException(ErrorCode.NOT_FOUND, "解析任务不存在");
        return task;
    }

    @Transactional
    DeleteResult deleteDocument(long documentId) {
        Map<String, Object> document = repo.document(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        var principal = SecurityUsers.current();
        long creator = number(document, "create_by", "createBy");
        boolean administrator = principal.roles().stream()
                .anyMatch(role -> "ADMIN".equals(role) || "ROLE_ADMIN".equals(role));
        if (!administrator && creator != principal.userId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除本人创建的文档");
        }
        if (repo.logicalDelete(documentId) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        long taskId = repo.createDeleteIndexTask(documentId);
        String indexStatus = compensationService.process(taskId);
        return new DeleteResult(documentId, taskId, indexStatus);
    }

    Map<String, Object> indexTask(long taskId) {
        Map<String, Object> task = repo.indexTask(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "索引补偿任务不存在");
        }
        return task;
    }

    ParsedDocument parseFile(long id) throws Exception {
        Map<String, Object> document = repo.document(id);
        if (document == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        repo.parsing(id);
        String extension = String.valueOf(document.get("file_type"));
        String path = String.valueOf(document.get("storage_path"));
        return new ParsedDocument(id, parser.chunks(parser.parse(storage.resolve(path), extension)));
    }

    @Transactional
    boolean completeParse(String eventId, long taskId, ParsedDocument parsed) {
        if (repo.consumeOnce("knowledge-document-parser", eventId) == 0) {
            return false;
        }
        repo.taskProcessing(taskId);
        repo.parsed(parsed.documentId(), parsed.chunks());
        indexService.indexDocument(parsed.documentId());
        repo.taskSuccess(taskId);
        return true;
    }

    List<Map<String, Object>> chunks(long id) {
        Map<String, Object> document = repo.document(id);
        if (document == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        var principal = SecurityUsers.current();
        String visibility = String.valueOf(document.get("visibility"));
        long creator = number(document, "create_by", "createBy");
        if (!administrator(principal.roles())
                && !"PUBLIC".equals(visibility)
                && creator != principal.userId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该文档切片");
        }
        return repo.chunks(id);
    }

    List<Map<String, Object>> search(String query, int topK) {
        return search(query, topK, null);
    }

    List<Map<String, Object>> search(String query, int topK, Long documentId) {
        int limit = Math.min(Math.max(topK, 1), 20);
        var principal = SecurityUsers.current();
        boolean administrator = principal.roles().stream()
                .anyMatch(role -> "ADMIN".equals(role) || "ROLE_ADMIN".equals(role));
        if (indexService.enabled()) {
            try {
                List<Map<String, Object>> vectorMatches =
                        indexService.search(
                                query, principal.userId(), administrator, limit, documentId);
                if (!vectorMatches.isEmpty()) {
                    return vectorMatches;
                }
            } catch (RuntimeException exception) {
                LOG.warn("向量检索失败，已安全降级到权限过滤后的文本检索");
            }
        }
        Map<Object, Map<String, Object>> unique = new LinkedHashMap<>();
        // 优先使用完整问题匹配；没有结果时再拆分中英文检索词，避免一开始扩大召回范围。
        addMatches(unique, query, limit, principal.userId(), administrator, documentId);
        if (unique.isEmpty()) {
            for (String term : retrievalTerms(query)) {
                addMatches(
                        unique, term, limit, principal.userId(), administrator, documentId);
                if (unique.size() >= limit) {
                    break;
                }
            }
        }
        return unique.values().stream().limit(limit).toList();
    }

    int reindexAll() {
        if (!indexService.enabled()) {
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "向量服务尚未配置");
        }
        return indexService.reindexAll();
    }

    private void addMatches(
            Map<Object, Map<String, Object>> unique,
            String term,
            int limit,
            long userId,
            boolean administrator,
            Long documentId) {
        if (term == null || term.isBlank()) {
            return;
        }
        for (Map<String, Object> row :
                repo.search(term, limit, userId, administrator, documentId)) {
            Object id =
                    row.getOrDefault("chunkId", row.getOrDefault("chunkid", row.get("CHUNKID")));
            unique.putIfAbsent(id, row);
        }
    }

    private List<String> retrievalTerms(String query) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = LATIN_TERM.matcher(query);
        while (matcher.find()) {
            terms.add(matcher.group());
        }
        String han = query.replaceAll("[^\\p{IsHan}]", "");
        // SQL fallback 没有分词器，使用中文三元组提供最低限度的召回能力。
        for (int i = 0; i + 3 <= han.length(); i++) {
            terms.add(han.substring(i, i + 3));
        }
        return terms;
    }

    private String normalizeVisibility(String requested, List<String> roles) {
        String visibility = requested == null ? "PRIVATE" : requested.trim().toUpperCase();
        if (!"PUBLIC".equals(visibility) && !"PRIVATE".equals(visibility)) {
            throw new BusinessException(ErrorCode.VALIDATION, "visibility 仅支持 PUBLIC 或 PRIVATE");
        }
        boolean publisher = roles.stream().anyMatch(role -> role.endsWith("ADMIN") || role.endsWith("OPS"));
        if ("PUBLIC".equals(visibility) && !publisher) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有 OPS 或 ADMIN 可以发布公共知识文档");
        }
        return visibility;
    }

    private boolean administrator(List<String> roles) {
        return roles.stream().anyMatch(role -> "ADMIN".equals(role) || "ROLE_ADMIN".equals(role));
    }

    private long number(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        return 0L;
    }

    /**
     * 返回文档软删除结果和对应的 Elasticsearch 补偿任务状态。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record DeleteResult(long documentId, long taskId, String indexStatus) {}

    /**
     * 已在事务外完成文件读取的文档切片结果。
     *
     * @author heyu
     * @since 2026/8/23
     */
    record ParsedDocument(long documentId, List<String> chunks) {}
}
