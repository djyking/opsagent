package com.opsagent.knowledge;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

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
 * @since 2026/9/2
 */
@Service
public class KnowledgeService {
    private static final Pattern LATIN_TERM = Pattern.compile("[A-Za-z0-9_]{2,}");
    private final KnowledgeRepository repo;
    private final FileStorageService storage;
    private final DocumentParserService parser;

    KnowledgeService(
            KnowledgeRepository repo, FileStorageService storage, DocumentParserService parser) {
        this.repo = repo;
        this.storage = storage;
        this.parser = parser;
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

    long upload(long base, MultipartFile file) {
        try {
            return repo.addDocument(base, storage.store(file), SecurityUsers.current().userId());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败：" + e.getMessage());
        }
    }

    @Transactional
    void parse(long id) {
        Map<String, Object> document = repo.document(id);
        if (document == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        repo.parsing(id);
        try {
            String extension = String.valueOf(document.get("file_type"));
            String path = String.valueOf(document.get("storage_path"));
            repo.parsed(id, parser.chunks(parser.parse(storage.resolve(path), extension)));
        } catch (Exception e) {
            repo.failed(id, e.getMessage());
            throw new BusinessException(ErrorCode.VALIDATION, "文档解析失败：" + e.getMessage());
        }
    }

    List<Map<String, Object>> chunks(long id) {
        return repo.chunks(id);
    }

    List<Map<String, Object>> search(String query, int topK) {
        int limit = Math.min(Math.max(topK, 1), 20);
        Map<Object, Map<String, Object>> unique = new LinkedHashMap<>();
        // 优先使用完整问题匹配；没有结果时再拆分中英文检索词，避免一开始扩大召回范围。
        addMatches(unique, query, limit);
        if (unique.isEmpty()) {
            for (String term : retrievalTerms(query)) {
                addMatches(unique, term, limit);
                if (unique.size() >= limit) break;
            }
        }
        return unique.values().stream().limit(limit).toList();
    }

    private void addMatches(Map<Object, Map<String, Object>> unique, String term, int limit) {
        if (term == null || term.isBlank()) return;
        for (Map<String, Object> row : repo.search(term, limit)) {
            Object id =
                    row.getOrDefault("chunkId", row.getOrDefault("chunkid", row.get("CHUNKID")));
            unique.putIfAbsent(id, row);
        }
    }

    private List<String> retrievalTerms(String query) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = LATIN_TERM.matcher(query);
        while (matcher.find()) terms.add(matcher.group());
        String han = query.replaceAll("[^\\p{IsHan}]", "");
        // SQL fallback 没有分词器，使用中文三元组提供最低限度的召回能力。
        for (int i = 0; i + 3 <= han.length(); i++) terms.add(han.substring(i, i + 3));
        return terms;
    }
}
