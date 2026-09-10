package com.opsagent.knowledge;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Durable, owner-scoped document processing for a fixed-lived visitor experience.
 *
 * @author heyu
 */
@Service
class VisitorKnowledgeService {
    private static final Logger LOG = LoggerFactory.getLogger(VisitorKnowledgeService.class);
    static final int MAX_FILES = 3;
    static final long MAX_FILE_BYTES = 5 * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 15 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KnowledgeRepository repo;
    private final FileStorageService storage;
    private final DocumentParserService parser;
    private final KnowledgeIndexService index;
    private final EmbeddingClient embedding;
    private final QdrantVectorStore vectors;
    private final ElasticsearchVectorStore keywords;
    private final VisitorKnowledgeLeaseClient leases;
    private final TicketAccessClient ticketAccess;
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0L,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    runnable -> {
                        var t = new Thread(runnable, "visitor-knowledge");
                        t.setDaemon(true);
                        return t;
                    });
    private final ThreadPoolExecutor cleaners =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(100),
                    runnable -> {
                        var t = new Thread(runnable, "visitor-knowledge-cleanup");
                        t.setDaemon(true);
                        return t;
                    });
    private final Set<Long> cleaning = ConcurrentHashMap.newKeySet();

    VisitorKnowledgeService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            KnowledgeRepository repo,
            FileStorageService storage,
            DocumentParserService parser,
            KnowledgeIndexService index,
            EmbeddingClient embedding,
            QdrantVectorStore vectors,
            ElasticsearchVectorStore keywords,
            VisitorKnowledgeLeaseClient leases,
            TicketAccessClient ticketAccess) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.repo = repo;
        this.storage = storage;
        this.parser = parser;
        this.index = index;
        this.embedding = embedding;
        this.vectors = vectors;
        this.keywords = keywords;
        this.leases = leases;
        this.ticketAccess = ticketAccess;
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
        cleaners.shutdownNow();
    }

    private long visitor() {
        var principal = SecurityUsers.current();
        if (principal.userId() >= 0
                || principal.roles().stream()
                        .noneMatch(r -> r.equals("DEMO") || r.equals("ROLE_DEMO")))
            throw new BusinessException(ErrorCode.FORBIDDEN, "体验库仅供当前访客本人使用");
        return principal.userId();
    }

    Map<String, Object> overview() {
        long owner = visitor();
        Map<String, Object> space = space(owner);
        List<Map<String, Object>> documents = ownDocuments(owner);
        return Map.of(
                "baseId",
                number(space, "base_id"),
                "name",
                "我的体验库",
                "expiresAt",
                instant(space.get("expires_at")),
                "documents",
                documents,
                "limits",
                Map.of(
                        "files",
                        MAX_FILES,
                        "fileBytes",
                        MAX_FILE_BYTES,
                        "totalBytes",
                        MAX_TOTAL_BYTES,
                        "characters",
                        100_000,
                        "chunks",
                        200,
                        "concurrent",
                        1),
                "usedBytes",
                documents.stream().mapToLong(row -> number(row, "file_size")).sum());
    }

    private Map<String, Object> space(long owner) {
        var lease = leases.read(owner);
        if (!lease.active()) throw expired();
        return tx.execute(
                status -> {
                    lockWorkers();
                    var rows =
                            jdbc.queryForList(
                                    "SELECT * FROM visitor_knowledge_space WHERE user_id=? FOR"
                                            + " UPDATE",
                                    owner);
                    if (rows.isEmpty()) {
                        long base =
                                repo.createBase("visitor-experience-" + owner, "仅当前体验身份可见", owner);
                        jdbc.update(
                                "UPDATE knowledge_base SET status='experience' WHERE id=?", base);
                        jdbc.update(
                                "INSERT INTO"
                                    + " visitor_knowledge_space(user_id,base_id,expires_at,checked_at)"
                                    + " VALUES(?,?,?,?)",
                                owner,
                                base,
                                Timestamp.from(lease.expiresAt()),
                                Timestamp.from(Instant.now()));
                    }
                    Map<String, Object> result =
                            spaceRows(
                                            "SELECT * FROM visitor_knowledge_space WHERE user_id=?",
                                            owner)
                                    .get(0);
                    if (result.get("revoked_at") != null
                            || !instant(result.get("expires_at")).isAfter(Instant.now()))
                        throw expired();
                    return result;
                });
    }

    long upload(MultipartFile file) {
        return upload(file, null);
    }

    long upload(MultipartFile file, Long ticketId) {
        long owner = visitor();
        if (ticketId != null) ticketAccess.requireCompletedVisitorDrill(ticketId);
        space(owner);
        if (file == null || file.isEmpty()) throw validation("请选择有内容的文件");
        if (file.getSize() > MAX_FILE_BYTES) throw validation("单份文件不能超过 5 MB");
        return tx.execute(
                status -> {
                    var row =
                            jdbc.queryForMap(
                                    "SELECT * FROM visitor_knowledge_space WHERE user_id=? FOR"
                                            + " UPDATE",
                                    owner);
                    requireLive(owner);
                    // Lock the owner before checking the linked result so retries and concurrent
                    // saves return the same document without overwriting its reviewed content.
                    if (ticketId != null) {
                        var existing =
                                jdbc.queryForList(
                                        "SELECT d.id FROM knowledge_document d JOIN"
                                            + " visitor_knowledge_document v ON v.document_id=d.id"
                                            + " WHERE v.user_id=? AND d.ticket_id=? AND d.deleted=0"
                                            + " AND v.deleted_at IS NULL ORDER BY d.id LIMIT 1",
                                        Long.class,
                                        owner,
                                        ticketId);
                        if (!existing.isEmpty()) return existing.get(0);
                    }
                    var documents = ownDocuments(owner);
                    if (documents.size() >= MAX_FILES) throw validation("体验库最多保留 3 份文件，请删除后再上传");
                    if (documents.stream().mapToLong(d -> number(d, "file_size")).sum()
                                    + file.getSize()
                            > MAX_TOTAL_BYTES) throw validation("体验库总量不能超过 15 MB");
                    FileStorageService.StoredFile stored = null;
                    try {
                        stored = storage.store(file);
                        String rollbackPath = stored.relativePath();
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .registerSynchronization(
                                        new org.springframework.transaction.support
                                                .TransactionSynchronization() {
                                            @Override
                                            public void afterCompletion(int completion) {
                                                if (completion != STATUS_COMMITTED)
                                                    try {
                                                        Files.deleteIfExists(
                                                                storage.resolve(rollbackPath));
                                                    } catch (Exception failure) {
                                                        LOG.warn(
                                                                "Visitor rolled-back upload cleanup"
                                                                        + " failed");
                                                    }
                                            }
                                        });
                        requireLive(owner);
                        long id =
                                repo.addDocument(
                                        number(row, "base_id"), ticketId, stored, owner, "PRIVATE");
                        jdbc.update(
                                "UPDATE knowledge_document SET review_status='EXPERIENCE' WHERE"
                                        + " id=?",
                                id);
                        jdbc.update(
                                "INSERT INTO"
                                    + " visitor_knowledge_document(document_id,user_id,stage,update_time)"
                                    + " VALUES(?,?,'UPLOADED',NOW())",
                                id,
                                owner);
                        return id;
                    } catch (Exception exception) {
                        if (stored != null)
                            try {
                                Files.deleteIfExists(storage.resolve(stored.relativePath()));
                            } catch (Exception ignored) {
                                LOG.warn("Visitor upload rollback file cleanup failed");
                            }
                        if (exception instanceof BusinessException business) throw business;
                        throw validation(
                                exception instanceof IllegalArgumentException
                                        ? exception.getMessage()
                                        : "文件保存失败，请重试");
                    }
                });
    }

    void requestProcess(long documentId, boolean indexOnly) {
        long owner = visitor();
        requireOwner(documentId, owner);
        tx.executeWithoutResult(
                status -> {
                    jdbc.queryForMap(
                            "SELECT * FROM visitor_knowledge_space WHERE user_id=? FOR UPDATE",
                            owner);
                    Map<String, Object> document = requireOwner(documentId, owner);
                    if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM visitor_knowledge_document WHERE"
                                        + " user_id=? AND (processing_active=1 OR (stage='QUEUED'"
                                        + " AND deleted_at IS NULL))",
                                    Integer.class,
                                    owner)
                            > 0)
                        throw new BusinessException(ErrorCode.CONFLICT, "每次只能处理 1 份文件，请等待当前任务完成");
                    if ("INDEXED".equals(text(document, "stage"))) throw validation("此文档已加入本人问答");
                    if (indexOnly && repo.chunks(documentId).isEmpty())
                        throw validation("请先解析文档生成切片");
                    jdbc.update(
                            "UPDATE visitor_knowledge_document SET"
                                    + " stage='QUEUED',requested_action=?,error_message=NULL,"
                                    + "started_at=NULL,update_time=NOW()"
                                    + " WHERE document_id=? AND deleted_at IS NULL",
                            indexOnly ? "INDEX" : "PARSE",
                            documentId);
                });
    }

    void delete(long id) {
        long owner = visitor();
        requireOwner(id, owner);
        tx.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "UPDATE visitor_knowledge_document SET"
                                    + " deleted_at=?,stage='DELETED',update_time=NOW() WHERE"
                                    + " document_id=? AND user_id=? AND deleted_at IS NULL",
                            Timestamp.from(Instant.now()),
                            id,
                            owner);
                    repo.logicalDelete(id);
                });
    }

    boolean experienceDocument(long id) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM visitor_knowledge_document WHERE document_id=?",
                        Integer.class,
                        id)
                > 0;
    }

    void requireVisible(long id) {
        requireOwner(id, SecurityUsers.current().userId());
    }

    List<Map<String, Object>> baseDocuments(long baseId) {
        long owner = visitor();
        requireLive(owner);
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM visitor_knowledge_space WHERE user_id=? AND"
                                + " base_id=?",
                        Integer.class,
                        owner,
                        baseId)
                != 1) throw forbidden();
        return ownDocuments(owner);
    }

    private List<Map<String, Object>> ownDocuments(long owner) {
        return jdbc.queryForList(
                "SELECT"
                    + " d.id,d.knowledge_base_id,d.ticket_id,d.original_name,d.file_type,d.file_size,"
                    + "d.status,d.review_status,"
                    + "d.index_status,d.version,d.create_time,d.create_by,v.stage,v.error_message"
                    + " AS parse_error,v.embedding_tokens,v.embedding_unknown_calls,v.embedding_calls,(SELECT"
                    + " COUNT(*) FROM knowledge_chunk c WHERE c.document_id=d.id) AS"
                    + " chunk_count,(SELECT MAX(c.embedding_model) FROM knowledge_chunk c WHERE"
                    + " c.document_id=d.id) AS embedding_model FROM knowledge_document d JOIN"
                    + " visitor_knowledge_document v ON v.document_id=d.id WHERE v.user_id=? AND"
                    + " d.deleted=0 AND v.deleted_at IS NULL ORDER BY d.id DESC",
                owner);
    }

    List<Map<String, Object>> ticketDocuments(long ticketId) {
        long owner = visitor();
        var documents =
                ownDocuments(owner).stream()
                        .filter(row -> number(row, "ticket_id") == ticketId)
                        .toList();
        if (!documents.isEmpty()) requireLive(owner);
        return documents;
    }

    private Map<String, Object> requireOwner(long id, long owner) {
        var rows =
                jdbc.queryForList(
                        "SELECT v.*,d.storage_path,d.file_type,d.original_name,d.version FROM"
                                + " visitor_knowledge_document v JOIN knowledge_document d ON"
                                + " d.id=v.document_id WHERE v.document_id=? AND v.user_id=? AND"
                                + " v.deleted_at IS NULL AND d.deleted=0",
                        id,
                        owner);
        if (rows.isEmpty()) throw forbidden();
        requireLive(owner);
        return rows.get(0);
    }

    private void requireLive(long owner) {
        var rows =
                spaceRows(
                        "SELECT expires_at,revoked_at FROM visitor_knowledge_space WHERE user_id=?",
                        owner);
        if (rows.isEmpty()
                || rows.get(0).get("revoked_at") != null
                || !instant(rows.get(0).get("expires_at")).isAfter(Instant.now())) throw expired();
        var lease = leases.read(owner);
        if (!lease.active()) throw expired();
    }

    @Scheduled(fixedDelayString = "${ops.knowledge.experience.dispatch-delay-ms:1500}")
    void dispatch() {
        if (workers.getActiveCount() >= 2 || workers.isShutdown()) return;
        Map<String, Object> job =
                tx.execute(
                        status -> {
                            lockWorkers();
                            // Old process slots are recovered after a restart; a running worker has
                            // a shorter deadline.
                            jdbc.update(
                                    "UPDATE visitor_knowledge_document SET stage=CASE WHEN"
                                        + " deleted_at IS NULL THEN 'FAILED' ELSE 'DELETED'"
                                        + " END,processing_active=0,error_message='处理超时或服务重启，请重试',update_time=NOW()"
                                        + " WHERE processing_active=1 AND started_at<?",
                                    Timestamp.from(Instant.now().minusSeconds(1200)));
                            if (jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM visitor_knowledge_document WHERE"
                                                    + " processing_active=1",
                                            Integer.class)
                                    >= 2) return null;
                            var rows =
                                    jdbc.queryForList(
                                            "SELECT v.* FROM visitor_knowledge_document v JOIN"
                                                + " visitor_knowledge_space s ON"
                                                + " s.user_id=v.user_id WHERE v.stage='QUEUED' AND"
                                                + " v.deleted_at IS NULL AND s.revoked_at IS NULL"
                                                + " AND s.expires_at>? ORDER BY"
                                                + " v.update_time,v.document_id LIMIT 1",
                                            Timestamp.from(Instant.now()));
                            if (rows.isEmpty()) return null;
                            var result = rows.get(0);
                            jdbc.update(
                                    "UPDATE visitor_knowledge_document SET"
                                        + " stage=?,processing_active=1,started_at=?,update_time=NOW()"
                                        + " WHERE document_id=?",
                                    "INDEX".equals(text(result, "requested_action"))
                                            ? "INDEXING"
                                            : "PARSING",
                                    Timestamp.from(Instant.now()),
                                    number(result, "document_id"));
                            return result;
                        });
        if (job == null) return;
        try {
            workers.execute(
                    () ->
                            process(
                                    number(job, "document_id"),
                                    number(job, "user_id"),
                                    "INDEX".equals(text(job, "requested_action"))));
        } catch (RejectedExecutionException exception) {
            jdbc.update(
                    "UPDATE visitor_knowledge_document SET"
                        + " stage='QUEUED',processing_active=0,started_at=NULL WHERE document_id=?"
                        + " AND deleted_at IS NULL",
                    number(job, "document_id"));
        }
    }

    void process(long id, long owner, boolean indexOnly) {
        Instant deadline = Instant.now().plusSeconds(900);
        Runnable guard =
                () -> {
                    if (!Instant.now().isBefore(deadline) || Thread.currentThread().isInterrupted())
                        throw validation("处理超过 15 分钟，请重试");
                    requireOwner(id, owner);
                };
        try {
            Map<String, Object> document = requireOwner(id, owner);
            if (!indexOnly) {
                guard.run();
                repo.parsing(id);
                var chunks =
                        parser.parseExperience(
                                storage.resolve(text(document, "storage_path")),
                                text(document, "file_type"),
                                text(document, "original_name"),
                                (int) number(document, "version"));
                guard.run();
                tx.executeWithoutResult(
                        status -> {
                            jdbc.queryForMap(
                                    "SELECT document_id FROM visitor_knowledge_document WHERE"
                                            + " document_id=? FOR UPDATE",
                                    id);
                            guard.run();
                            repo.parsed(id, chunks);
                            jdbc.update(
                                    "UPDATE visitor_knowledge_document SET"
                                        + " stage='INDEXING',update_time=NOW() WHERE document_id=?"
                                        + " AND deleted_at IS NULL",
                                    id);
                        });
            }
            guard.run();
            index.indexExperience(id, guard, usage -> recordUsage(id, usage));
            guard.run();
            jdbc.update(
                    "UPDATE visitor_knowledge_document SET"
                            + " stage='INDEXED',error_message=NULL,update_time=NOW() WHERE"
                            + " document_id=? AND deleted_at IS NULL AND stage='INDEXING'",
                    id);
            jdbc.update(
                    "UPDATE knowledge_document SET status='INDEXED' WHERE id=? AND deleted=0", id);
        } catch (Exception exception) {
            String message = safeMessage(exception);
            jdbc.update(
                    "UPDATE visitor_knowledge_document SET"
                        + " stage='FAILED',error_message=?,update_time=NOW() WHERE document_id=?"
                        + " AND deleted_at IS NULL",
                    message,
                    id);
            jdbc.update(
                    "UPDATE knowledge_document SET status=CASE WHEN EXISTS(SELECT 1 FROM"
                        + " knowledge_chunk c WHERE c.document_id=?) THEN 'PARSED' ELSE 'FAILED'"
                        + " END,index_status='FAILED',parse_error=? WHERE id=? AND deleted=0",
                    id,
                    message,
                    id);
            LOG.info(
                    "Visitor document processing stopped: documentId={}, type={}",
                    id,
                    exception.getClass().getSimpleName());
        } finally {
            jdbc.update(
                    "UPDATE visitor_knowledge_document SET processing_active=0 WHERE document_id=?",
                    id);
            // A revocation can race the final remote write; the worker always rechecks and purges.
            try {
                requireOwner(id, owner);
            } catch (BusinessException exception) {
                if (exception.getMessage().contains("体验已结束")
                        || exception.getMessage().contains("无权访问")) {
                    markDeleted(id);
                    try {
                        cleanupDocument(id);
                    } catch (RuntimeException failure) {
                        LOG.warn("Visitor cleanup will retry: documentId={}", id);
                    }
                }
            }
        }
    }

    private void recordUsage(long id, int usage) {
        jdbc.update(
                "UPDATE visitor_knowledge_document SET embedding_tokens=embedding_tokens+?,"
                    + "embedding_unknown_calls=embedding_unknown_calls+?,embedding_calls=embedding_calls+1"
                    + " WHERE document_id=?",
                Math.max(usage, 0),
                usage < 0 ? 1 : 0,
                id);
    }

    List<Map<String, Object>> search(String query, int limit, Long documentId) {
        return search(query, limit, documentId, new QueryEmbedding(embedding, query));
    }

    List<Map<String, Object>> search(
            String query, int limit, Long documentId, QueryEmbedding queryEmbedding) {
        query = new QueryNormalizer().normalize(query);
        long owner = SecurityUsers.current().userId();
        if (owner >= 0) {
            if (documentId != null) throw forbidden();
            return List.of();
        }
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM visitor_knowledge_space WHERE user_id=?",
                        Integer.class,
                        owner)
                == 0) return List.of();
        requireLive(owner);
        if (documentId != null && !"INDEXED".equals(text(requireOwner(documentId, owner), "stage")))
            throw new BusinessException(ErrorCode.CONFLICT, "文档尚未完成私有向量化，请先完成解析并加入本人问答");
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT document_id FROM visitor_knowledge_document WHERE user_id=? AND"
                                + " stage='INDEXED' AND deleted_at IS NULL"
                                + (documentId == null ? "" : " AND document_id=?"),
                        Long.class,
                        documentId == null
                                ? new Object[] {owner}
                                : new Object[] {owner, documentId});
        if (ids.isEmpty()) return List.of();
        if (!index.embeddingEnabled())
            throw new BusinessException(ErrorCode.MIDDLEWARE_UNAVAILABLE, "私有向量检索服务尚未配置");
        EmbeddingBatchResult result;
        try {
            result = queryEmbedding.get(query);
        } catch (RuntimeException exception) {
            recordUsage(ids.get(0), -1);
            throw new BusinessException(
                    ErrorCode.MIDDLEWARE_UNAVAILABLE, "私有向量检索的 Embedding 调用失败，请重试");
        }
        recordUsage(ids.get(0), result.tokenUsage());
        List<RetrievalHit> hits =
                vectors.experienceSearch(
                        result.vectors().get(0), owner, ids, limit, documentId != null);
        requireLive(owner);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var hit : hits) {
            long id = number(hit.source(), "documentId");
            long chunkId = number(hit.source(), "chunkId");
            if (!ids.contains(id)) continue;
            // Current SQL text and state are authoritative, never stale vector payloads.
            var current =
                    jdbc.queryForList(
                            "SELECT c.id AS chunkId,d.id AS documentId,d.original_name AS"
                                + " documentName,c.content,c.chunk_index AS"
                                + " chunkIndex,c.page_number AS page,d.version FROM knowledge_chunk"
                                + " c JOIN knowledge_document d ON d.id=c.document_id JOIN"
                                + " visitor_knowledge_document v ON v.document_id=d.id WHERE c.id=?"
                                + " AND d.id=? AND v.user_id=? AND v.stage='INDEXED' AND"
                                + " v.deleted_at IS NULL AND d.deleted=0",
                            chunkId,
                            id,
                            owner);
            if (!current.isEmpty()) {
                var row = current.get(0);
                row.put("score", hit.score());
                row.put("knowledgeScope", "MY_EXPERIENCE");
                row.put("retrievalMode", "VECTOR");
                rows.add(row);
            }
        }
        requireLive(owner);
        return rows;
    }

    @Scheduled(fixedDelayString = "${ops.knowledge.experience.cleanup-delay-ms:60000}")
    void cleanup() {
        var spaces =
                spaceRows(
                        "SELECT * FROM visitor_knowledge_space WHERE revoked_at IS NULL ORDER BY"
                                + " checked_at,user_id LIMIT 100");
        for (var space : spaces) {
            long owner = number(space, "user_id");
            try {
                boolean expired = !instant(space.get("expires_at")).isAfter(Instant.now());
                if (expired || !leases.read(owner).active()) {
                    jdbc.update(
                            "UPDATE visitor_knowledge_space SET revoked_at=?,checked_at=? WHERE"
                                    + " user_id=?",
                            Timestamp.from(Instant.now()),
                            Timestamp.from(Instant.now()),
                            owner);
                    jdbc.queryForList(
                                    "SELECT document_id FROM visitor_knowledge_document WHERE"
                                            + " user_id=? AND cleaned_at IS NULL",
                                    Long.class,
                                    owner)
                            .forEach(this::markDeleted);
                    jdbc.update(
                            "UPDATE knowledge_base SET deleted=1 WHERE id=?",
                            number(space, "base_id"));
                } else
                    jdbc.update(
                            "UPDATE visitor_knowledge_space SET checked_at=? WHERE user_id=?",
                            Timestamp.from(Instant.now()),
                            owner);
            } catch (RuntimeException exception) {
                LOG.warn("Visitor lease cleanup check unavailable: owner={}", owner);
            }
        }
        var documents =
                jdbc.queryForList(
                        "SELECT document_id FROM visitor_knowledge_document WHERE deleted_at IS NOT"
                                + " NULL AND cleaned_at IS NULL ORDER BY deleted_at LIMIT 100",
                        Long.class);
        for (long id : documents) {
            if (!cleaning.add(id)) continue;
            try {
                cleaners.execute(
                        () -> {
                            try {
                                cleanupDocument(id);
                            } catch (RuntimeException exception) {
                                LOG.warn("Visitor document cleanup will retry: documentId={}", id);
                            } finally {
                                cleaning.remove(id);
                            }
                        });
            } catch (RejectedExecutionException exception) {
                cleaning.remove(id);
            }
        }
    }

    private void markDeleted(long id) {
        jdbc.update(
                "UPDATE visitor_knowledge_document SET"
                    + " deleted_at=COALESCE(deleted_at,?),stage='DELETED',cleaned_at=NULL,update_time=NOW()"
                    + " WHERE document_id=?",
                Timestamp.from(Instant.now()),
                id);
        repo.logicalDelete(id);
    }

    void cleanupDocument(long id) {
        var rows =
                jdbc.queryForList(
                        "SELECT d.storage_path FROM knowledge_document d JOIN"
                            + " visitor_knowledge_document v ON v.document_id=d.id WHERE d.id=? AND"
                            + " v.deleted_at IS NOT NULL",
                        id);
        if (rows.isEmpty()) return;
        // Idempotent remote deletion comes first; failures keep the row eligible for retries.
        keywords.deleteExperienceDocument(id);
        vectors.deleteExperienceDocument(id);
        try {
            String path = text(rows.get(0), "storage_path");
            if (!path.isBlank()) Files.deleteIfExists(storage.resolve(path));
        } catch (Exception exception) {
            throw new IllegalStateException("体验文件清理失败", exception);
        }
        tx.executeWithoutResult(
                status -> {
                    jdbc.update("DELETE FROM knowledge_chunk WHERE document_id=?", id);
                    jdbc.update(
                            "UPDATE knowledge_document SET storage_path='',parse_error=NULL WHERE"
                                    + " id=? AND deleted=1",
                            id);
                    jdbc.update(
                            "UPDATE visitor_knowledge_document SET cleaned_at=?,update_time=NOW()"
                                    + " WHERE document_id=? AND deleted_at IS NOT NULL",
                            Timestamp.from(Instant.now()),
                            id);
                });
    }

    private void lockWorkers() {
        jdbc.queryForMap("SELECT id FROM visitor_knowledge_worker_lock WHERE id=1 FOR UPDATE");
    }

    private List<Map<String, Object>> spaceRows(String sql, Object... arguments) {
        return jdbc.query(sql, VisitorKnowledgeService::leaseRow, arguments);
    }

    static Map<String, Object> leaseRow(java.sql.ResultSet result, int rowNumber)
            throws java.sql.SQLException {
        Map<String, Object> row =
                new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(result, rowNumber);
        // DATETIME getObject() may be a zone-less LocalDateTime. Let the configured JDBC
        // connection convert it, exactly as it converted Timestamp parameters on insertion.
        row.put("expires_at", result.getTimestamp("expires_at").toInstant());
        return row;
    }

    private static BusinessException expired() {
        return new BusinessException(ErrorCode.FORBIDDEN, "体验已结束或到期，请重新进入体验");
    }

    private static BusinessException forbidden() {
        return new BusinessException(ErrorCode.FORBIDDEN, "无权访问此体验文档");
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp stamp) return stamp.toInstant();
        if (value instanceof java.time.LocalDateTime time)
            return time.toInstant(java.time.ZoneOffset.UTC);
        return Instant.parse(value.toString());
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = "处理失败，请重试";
        return message.substring(0, Math.min(900, message.length()));
    }
}
