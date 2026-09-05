package com.opsagent.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.mq.DocumentIndexRequested;
import com.opsagent.common.mq.DomainEvent;
import com.opsagent.common.mq.MqNames;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * 使用 JdbcTemplate 管理知识库、文档元数据和文本切片。
 *
 * @author heyu
 * @since 2026/8/19
 */
@Repository
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    KnowledgeRepository(JdbcTemplate j, ObjectMapper mapper) {
        jdbc = j;
        this.mapper = mapper;
    }

    long createBase(String name, String description, long user) {
        KeyHolder k = new GeneratedKeyHolder();
        jdbc.update(
                c -> {
                    PreparedStatement p =
                            c.prepareStatement(
                                    "INSERT INTO knowledge_base(name, description, status,"
                                            + " create_by, create_time, update_time, deleted)"
                                            + " VALUES(?,?,'enable',?,NOW(),NOW(),0)",
                                    Statement.RETURN_GENERATED_KEYS);
                    p.setString(1, name);
                    p.setString(2, description);
                    p.setLong(3, user);
                    return p;
                },
                k);
        return Objects.requireNonNull(k.getKey()).longValue();
    }

    List<Map<String, Object>> bases() {
        return jdbc.queryForList(
                "SELECT id,name,description,status,create_by,create_time,update_time FROM"
                        + " knowledge_base WHERE deleted=0 ORDER BY id DESC");
    }

    long addDocument(
            long base,
            Long ticketId,
            FileStorageService.StoredFile f,
            long user,
            String visibility) {
        KeyHolder k = new GeneratedKeyHolder();
        jdbc.update(
                c -> {
                    PreparedStatement p =
                            c.prepareStatement(
                                    "INSERT INTO knowledge_document(knowledge_base_id,ticket_id,file_name,"
                                        + " original_name, file_type, file_size, storage_path,"
                                        + " status, review_status, content_hash, version,"
                                        + " visibility, create_by, create_time,"
                                        + " update_time, deleted)"
                                        + " VALUES(?,?,?,?,?,?,?,'UPLOADED','DRAFT',?,1,?,?,NOW(),NOW(),0)",
                                    Statement.RETURN_GENERATED_KEYS);
                    p.setLong(1, base);
                    if (ticketId == null) p.setNull(2, Types.BIGINT);
                    else p.setLong(2, ticketId);
                    p.setString(3, PathName.file(f.relativePath()));
                    p.setString(4, f.originalName());
                    p.setString(5, f.extension());
                    p.setLong(6, f.size());
                    p.setString(7, f.relativePath());
                    p.setString(8, f.sha256());
                    p.setString(9, visibility);
                    p.setLong(10, user);
                    return p;
                },
                k);
        return Objects.requireNonNull(k.getKey()).longValue();
    }

    List<Map<String, Object>> documents(long base) {
        return jdbc.queryForList(
                "SELECT d.id,d.knowledge_base_id,d.original_name,d.file_type,d.file_size,"
                        + "d.status,d.review_status,d.index_status,d.visibility,d.version,"
                        + "d.content_hash,d.parse_error,d.create_by,d.create_time,d.update_time,"
                        + "COUNT(c.id) chunk_count,MAX(c.embedding_model) embedding_model"
                        + " FROM knowledge_document d LEFT JOIN knowledge_chunk c ON c.document_id=d.id"
                        + " WHERE d.knowledge_base_id=? AND d.deleted=0 GROUP BY d.id ORDER BY d.id DESC",
                base);
    }

    List<Map<String, Object>> ticketDocuments(long ticketId, long userId, boolean administrator) {
        return jdbc.queryForList(
                "SELECT id,knowledge_base_id,ticket_id,original_name,file_type,file_size,status,review_status,"
                        + "visibility,content_hash,parse_error,create_by,create_time,update_time FROM"
                        + " knowledge_document WHERE ticket_id=? AND deleted=0"
                        + " AND (?=1 OR visibility='PUBLIC' OR create_by=?) ORDER BY id DESC",
                ticketId,
                administrator ? 1 : 0,
                userId);
    }

    Map<String, Object> document(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM knowledge_document WHERE id=? AND deleted=0", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    void parsing(long id) {
        jdbc.update(
                "UPDATE knowledge_document SET status='PARSING',parse_error=NULL WHERE id=? AND"
                        + " status IN ('UPLOADED','FAILED','PARSED','INDEXED')",
                id);
    }

    long createParseTask(long documentId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "INSERT INTO document_parse_task(document_id,status,retry_count,"
                                            + "next_retry_time,create_time,update_time)"
                                            + " VALUES(?,'QUEUED',0,NOW(),NOW(),NOW())",
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, documentId);
                    return statement;
                },
                holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    Map<String, Object> parseTask(long taskId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM document_parse_task WHERE id=?", taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    int consumeOnce(String consumer, String eventId) {
        return jdbc.update(
                "INSERT IGNORE INTO mq_consumed_event(consumer_name,event_id,consume_time)"
                        + " VALUES(?,?,NOW())",
                consumer,
                eventId);
    }

    boolean consumed(String consumer, String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mq_consumed_event WHERE consumer_name=? AND event_id=?",
                Integer.class,
                consumer,
                eventId);
        return count != null && count > 0;
    }

    void taskProcessing(long taskId) {
        jdbc.update(
                "UPDATE document_parse_task SET status='PROCESSING',error_message=NULL,"
                        + "update_time=NOW() WHERE id=?",
                taskId);
    }

    void taskSuccess(long taskId) {
        jdbc.update(
                "UPDATE document_parse_task SET status='SUCCESS',next_retry_time=NULL,"
                        + "error_message=NULL,update_time=NOW() WHERE id=?",
                taskId);
    }

    void taskPublishFailed(long taskId, String message) {
        jdbc.update(
                "UPDATE document_parse_task SET status='PUBLISH_FAILED',error_message=?,"
                        + "update_time=NOW() WHERE id=?",
                safeMessage(message),
                taskId);
    }

    void taskAttemptFailed(long taskId, long documentId, String message, int maximumAttempts) {
        String safe = safeMessage(message);
        jdbc.update(
                "UPDATE document_parse_task SET retry_count=retry_count+1,"
                        + "status=IF(retry_count+1>=?,'FAILED','RETRYING'),"
                        + "next_retry_time=DATE_ADD(NOW(), INTERVAL POW(2,retry_count+1) SECOND),"
                        + "error_message=?,update_time=NOW() WHERE id=?",
                maximumAttempts,
                safe,
                taskId);
        failed(documentId, safe);
    }

    void parsed(long id, List<StructuredChunk> chunks) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE document_id=?", id);
        for (int i = 0; i < chunks.size(); i++) {
            StructuredChunk chunk = chunks.get(i);
            jdbc.update(
                    "INSERT INTO"
                        + " knowledge_chunk(document_id,chunk_index,content,token_count,embedding_status,"
                        + " page_number,metadata_json,create_time)"
                        + " VALUES(?,?,?,?,'PENDING',?,?,NOW())",
                    id,
                    i,
                    chunk.content(),
                    chunk.tokenCount(),
                    chunk.pageStart(),
                    metadataJson(chunk));
        }
        jdbc.update(
                "UPDATE knowledge_document SET status='PARSED',index_status='PENDING',"
                        + "parse_error=NULL WHERE id=?",
                id);
    }

    void failed(long id, String message) {
        jdbc.update(
                "UPDATE knowledge_document SET status='FAILED',parse_error=? WHERE id=?",
                message,
                id);
    }

    List<Map<String, Object>> chunks(long id) {
        return jdbc.queryForList(
                "SELECT"
                    + " id,document_id,chunk_index,content,token_count,embedding_status,"
                    + " embedding_model,indexed_at,page_number,metadata_json,create_time"
                    + " FROM knowledge_chunk WHERE document_id=? ORDER BY chunk_index",
                id);
    }

    List<Map<String, Object>> search(
            String query,
            int limit,
            long userId,
            boolean administrator,
            Long documentId) {
        String like = "%" + query + "%";
        String documentFilter = documentId == null ? "" : " AND d.id=?";
        List<Object> arguments = new ArrayList<>();
        arguments.add(like);
        arguments.add(administrator ? 1 : 0);
        arguments.add(userId);
        if (documentId != null) {
            arguments.add(documentId);
        }
        arguments.add(limit);
        String sql = "SELECT c.id chunkId,c.document_id documentId,c.chunk_index"
                + " chunkIndex,c.content,d.original_name documentName,c.page_number page,"
                + " d.version,d.update_time updateTime FROM knowledge_chunk c"
                + " JOIN knowledge_document d ON d.id=c.document_id WHERE c.content LIKE ? AND"
                + " d.deleted=0 AND d.review_status='PUBLISHED'"
                + " AND (?=1 OR d.visibility='PUBLIC' OR d.create_by=?)"
                + documentFilter + " ORDER BY c.id DESC LIMIT ?";
        return jdbc.queryForList(sql, arguments.toArray());
    }

    List<Long> indexableDocumentIds() {
        return jdbc.queryForList(
                "SELECT id FROM knowledge_document WHERE deleted=0 AND review_status='PUBLISHED'"
                        + " AND status IN ('PARSED','INDEXED')"
                        + " ORDER BY id",
                Long.class);
    }

    List<Map<String, Object>> scopedChunks(Long documentId, Long ticketId, long userId,
                                            boolean administrator, int limit, List<String> terms) {
        if (documentId == null && ticketId == null) throw new IllegalArgumentException("必须指定文档或工单范围");
        StringBuilder sql = new StringBuilder("""
                SELECT c.id chunkId,c.document_id documentId,c.chunk_index chunkIndex,c.content,
                       d.original_name documentName,c.page_number page,d.version,d.update_time updateTime
                FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
                WHERE d.deleted=0 AND d.status IN ('PARSED','INDEXED')
                  AND ((d.review_status='PUBLISHED' AND (?=1 OR d.visibility='PUBLIC' OR d.create_by=?))
                    OR (d.review_status IN ('DRAFT','IN_REVIEW','REJECTED') AND (?=1 OR d.create_by=?)))
                """);
        List<Object> arguments = new ArrayList<>(List.of(administrator ? 1 : 0, userId, administrator ? 1 : 0, userId));
        if (documentId != null) {
            sql.append(" AND d.id=?");
            arguments.add(documentId);
        }
        if (ticketId != null) {
            sql.append(" AND d.ticket_id=?");
            arguments.add(ticketId);
        }
        if (!terms.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < terms.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("LOWER(c.content) LIKE ? OR LOWER(d.original_name) LIKE ?");
                String like = "%" + terms.get(i).toLowerCase(Locale.ROOT) + "%";
                arguments.add(like);
                arguments.add(like);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY d.id DESC,c.chunk_index LIMIT ?");
        arguments.add(limit);
        return jdbc.query(sql.toString(), (row, index) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chunkId", row.getLong("chunkId"));
            result.put("documentId", row.getLong("documentId"));
            result.put("chunkIndex", row.getInt("chunkIndex"));
            result.put("content", row.getString("content"));
            result.put("documentName", row.getString("documentName"));
            result.put("page", row.getObject("page"));
            result.put("version", row.getInt("version"));
            result.put("updateTime", row.getObject("updateTime"));
            return result;
        }, arguments.toArray());
    }

    List<Map<String, Object>> reviewDocuments(String status) {
        String reviewStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return jdbc.queryForList(
                """
                SELECT d.id,d.knowledge_base_id knowledgeBaseId,b.name knowledgeBaseName,
                       d.original_name originalName,d.status parseStatus,
                       d.review_status reviewStatus,d.visibility,d.create_by createBy,
                       d.submitted_by submittedBy,d.submitted_time submittedTime,
                       d.reviewer_id reviewerId,d.review_time reviewTime,
                       d.publish_time publishTime,d.review_comment reviewComment,
                       d.create_time createTime,d.update_time updateTime
                FROM knowledge_document d
                JOIN knowledge_base b ON b.id=d.knowledge_base_id
                WHERE d.deleted=0 AND (?='' OR d.review_status=?)
                ORDER BY FIELD(d.review_status,'IN_REVIEW','DRAFT','REJECTED','PUBLISHED','ARCHIVED'),
                         d.update_time DESC
                LIMIT 500
                """,
                reviewStatus,
                reviewStatus);
    }

    int submitReview(long documentId, long userId) {
        return jdbc.update(
                """
                UPDATE knowledge_document SET review_status='IN_REVIEW',submitted_by=?,
                    submitted_time=NOW(),reviewer_id=NULL,review_time=NULL,
                    review_comment=NULL,update_time=NOW()
                WHERE id=? AND deleted=0 AND create_by=?
                  AND status IN ('PARSED','INDEXED')
                  AND review_status IN ('DRAFT','REJECTED')
                """,
                userId,
                documentId,
                userId);
    }

    int approveReview(long documentId, long reviewerId, String comment) {
        return jdbc.update(
                """
                UPDATE knowledge_document SET review_status='PUBLISHED',reviewer_id=?,
                    review_time=NOW(),publish_time=NOW(),review_comment=?,update_time=NOW()
                WHERE id=? AND deleted=0 AND review_status='IN_REVIEW'
                """,
                reviewerId,
                comment,
                documentId);
    }

    int rejectReview(long documentId, long reviewerId, String comment) {
        return jdbc.update(
                """
                UPDATE knowledge_document SET review_status='REJECTED',reviewer_id=?,
                    review_time=NOW(),publish_time=NULL,review_comment=?,update_time=NOW()
                WHERE id=? AND deleted=0 AND review_status='IN_REVIEW'
                """,
                reviewerId,
                comment,
                documentId);
    }

    int archive(long documentId, long reviewerId, String comment) {
        return jdbc.update(
                """
                UPDATE knowledge_document SET review_status='ARCHIVED',reviewer_id=?,
                    review_time=NOW(),review_comment=?,update_time=NOW()
                WHERE id=? AND deleted=0 AND review_status='PUBLISHED'
                """,
                reviewerId,
                comment,
                documentId);
    }

    void reviewHistory(
            long documentId,
            String fromStatus,
            String toStatus,
            long operatorId,
            String comment) {
        jdbc.update(
                """
                INSERT INTO knowledge_review_history(
                    document_id,from_status,to_status,operator_id,comment,create_time)
                VALUES(?,?,?,?,?,NOW())
                """,
                documentId,
                fromStatus,
                toStatus,
                operatorId,
                comment);
    }

    List<Map<String, Object>> reviewHistory(long documentId) {
        return jdbc.queryForList(
                """
                SELECT id,document_id documentId,from_status fromStatus,to_status toStatus,
                       operator_id operatorId,comment,create_time createTime
                FROM knowledge_review_history WHERE document_id=? ORDER BY id
                """,
                documentId);
    }

    void markIndexResults(
            long documentId,
            List<Long> succeededChunkIds,
            Map<Long, String> failures,
            String model) {
        for (Long chunkId : succeededChunkIds) {
            jdbc.update(
                    "UPDATE knowledge_chunk SET embedding_status='INDEXED',embedding_model=?,"
                            + "indexed_at=NOW() WHERE id=?",
                    model,
                    chunkId);
        }
        for (Long chunkId : failures.keySet()) {
            jdbc.update(
                    "UPDATE knowledge_chunk SET embedding_status='FAILED',embedding_model=?,"
                            + "indexed_at=NULL WHERE id=?",
                    model,
                    chunkId);
        }
        if (failures.isEmpty()) {
            jdbc.update(
                    "UPDATE knowledge_document SET index_status='SUCCESS',parse_error=NULL WHERE id=?",
                    documentId);
        } else {
            String firstFailure = failures.values().iterator().next();
            jdbc.update(
                    "UPDATE knowledge_document SET index_status='FAILED',parse_error=? WHERE id=?",
                    safeMessage(firstFailure),
                    documentId);
        }
    }

    long createIndexTaskAndOutbox(long documentId, int documentVersion, String strategyVersion) {
        jdbc.update(
                            "INSERT INTO knowledge_index_task(document_id,document_version,operation,"
                                    + "status,retry_count,next_retry_time,create_time,update_time)"
                                    + " VALUES(?,?,'INDEX','PENDING',0,NOW(),NOW(),NOW())"
                                    + " ON DUPLICATE KEY UPDATE "
                                    + "document_version=VALUES(document_version),status='PENDING',"
                                    + "retry_count=0,next_retry_time=NOW(),error_message=NULL,"
                                    + "update_time=NOW()",
                documentId, documentVersion);
        // MySQL upserts may report multiple generated keys; the unique business key is stable.
        long taskId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM knowledge_index_task WHERE document_id=? AND operation='INDEX'",
                Long.class, documentId));
        String eventId = UUID.randomUUID().toString();
        DomainEvent<DocumentIndexRequested> event = new DomainEvent<>(
                eventId,
                MqNames.DOCUMENT_INDEX_ROUTING_KEY,
                Instant.now(),
                new DocumentIndexRequested(
                        documentId, taskId, documentVersion, strategyVersion));
        try {
            jdbc.update(
                    "INSERT INTO knowledge_event_outbox(event_id,event_type,payload,status,"
                            + "retry_count,next_retry_time,create_time,update_time)"
                            + " VALUES(?,?,?,'PENDING',0,NOW(),NOW(),NOW())",
                    eventId,
                    MqNames.DOCUMENT_INDEX_ROUTING_KEY,
                    mapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("索引事件无法序列化", exception);
        }
        return taskId;
    }

    List<Map<String, Object>> dueOutboxEvents(int limit) {
        return jdbc.queryForList(
                "SELECT id,event_id,event_type,payload,retry_count FROM knowledge_event_outbox"
                        + " WHERE (status IN ('PENDING','RETRYING')"
                        + " AND (next_retry_time IS NULL OR next_retry_time<=NOW()))"
                        + " OR (status='SENDING' AND update_time<=DATE_SUB(NOW(),INTERVAL 5 MINUTE))"
                        + " ORDER BY id LIMIT ?",
                limit);
    }

    int claimOutbox(long id) {
        return jdbc.update(
                "UPDATE knowledge_event_outbox SET status='SENDING',update_time=NOW()"
                        + " WHERE id=? AND ((status IN ('PENDING','RETRYING')"
                        + " AND (next_retry_time IS NULL OR next_retry_time<=NOW()))"
                        + " OR (status='SENDING'"
                        + " AND update_time<=DATE_SUB(NOW(),INTERVAL 5 MINUTE)))",
                id);
    }

    void outboxSent(long id) {
        jdbc.update(
                "UPDATE knowledge_event_outbox SET status='SENT',last_error=NULL,"
                        + "next_retry_time=NULL,update_time=NOW() WHERE id=?",
                id);
    }

    void outboxFailed(long id, String message) {
        jdbc.update(
                "UPDATE knowledge_event_outbox SET status='RETRYING',retry_count=retry_count+1,"
                        + "next_retry_time=DATE_ADD(NOW(),INTERVAL LEAST(300,POW(2,retry_count+1)) SECOND),"
                        + "last_error=?,update_time=NOW() WHERE id=?",
                safeMessage(message),
                id);
    }

    boolean validDocumentVersion(long documentId, int version) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_document WHERE id=? AND version=? AND deleted=0",
                Integer.class,
                documentId,
                version);
        return count != null && count > 0;
    }

    long createReindexTask(long userId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO knowledge_reindex_task(status,create_by,create_time,update_time)"
                                    + " VALUES('PENDING',?,NOW(),NOW())",
                            Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, userId);
                    return statement;
                },
                holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    List<Long> pendingReindexTasks() {
        return jdbc.queryForList(
                "SELECT id FROM knowledge_reindex_task WHERE status='PENDING' ORDER BY id LIMIT 1",
                Long.class);
    }

    int claimReindexTask(long taskId, String targetIndex, int documentTotal) {
        return jdbc.update(
                "UPDATE knowledge_reindex_task SET status='RUNNING',target_index=?,"
                        + "document_total=?,start_time=NOW(),update_time=NOW()"
                        + " WHERE id=? AND status='PENDING'",
                targetIndex,
                documentTotal,
                taskId);
    }

    void updateReindexProgress(long taskId, int success, int failure, int chunks) {
        jdbc.update(
                "UPDATE knowledge_reindex_task SET document_success=?,document_failure=?,"
                        + "chunk_total=?,update_time=NOW() WHERE id=?",
                success,
                failure,
                chunks,
                taskId);
    }

    void completeReindexTask(long taskId) {
        jdbc.update(
                "UPDATE knowledge_reindex_task SET status='SUCCESS',finish_time=NOW(),"
                        + "error_message=NULL,update_time=NOW() WHERE id=?",
                taskId);
    }

    void failReindexTask(long taskId, String message) {
        jdbc.update(
                "UPDATE knowledge_reindex_task SET status='FAILED',finish_time=NOW(),"
                        + "error_message=?,update_time=NOW() WHERE id=?",
                safeMessage(message),
                taskId);
    }

    Map<String, Object> reindexTask(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM knowledge_reindex_task WHERE id=?", taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    Map<String, Long> indexStatusCounts() {
        long published = count(
                "SELECT COUNT(*) FROM knowledge_document WHERE deleted=0 AND review_status='PUBLISHED'");
        long indexed = count(
                "SELECT COUNT(*) FROM knowledge_document WHERE deleted=0"
                        + " AND review_status='PUBLISHED' AND index_status='SUCCESS'");
        long pending = count(
                "SELECT COUNT(*) FROM knowledge_document WHERE deleted=0"
                        + " AND review_status='PUBLISHED' AND index_status IN ('PENDING','PROCESSING')");
        long failed = count(
                "SELECT COUNT(*) FROM knowledge_document WHERE deleted=0"
                        + " AND review_status='PUBLISHED' AND index_status='FAILED'");
        long publishedChunks = count(
                "SELECT COUNT(*) FROM knowledge_chunk c JOIN knowledge_document d"
                        + " ON d.id=c.document_id WHERE d.deleted=0"
                        + " AND d.review_status='PUBLISHED'");
        return Map.of(
                "published", published,
                "indexed", indexed,
                "pending", pending,
                "failed", failed,
                "publishedChunks", publishedChunks);
    }

    List<Map<String, Object>> failedIndexTasks() {
        return jdbc.queryForList(
                "SELECT id,document_id documentId,document_version documentVersion,operation,"
                        + "status,retry_count retryCount,error_message lastError,"
                        + "create_time createTime,update_time updateTime"
                        + " FROM knowledge_index_task WHERE status='FAILED'"
                        + " ORDER BY update_time DESC LIMIT 200");
    }

    void markIndexPending(long documentId) {
        jdbc.update(
                "UPDATE knowledge_document SET index_status='PENDING',update_time=NOW()"
                        + " WHERE id=? AND deleted=0",
                documentId);
    }

    void markIndexSuccess(long documentId) {
        jdbc.update(
                "UPDATE knowledge_document SET index_status='SUCCESS',parse_error=NULL,"
                        + "update_time=NOW() WHERE id=? AND deleted=0",
                documentId);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    int logicalDelete(long documentId) {
        return jdbc.update(
                "UPDATE knowledge_document SET deleted=1,status='DELETED',update_time=NOW()"
                        + " WHERE id=? AND deleted=0",
                documentId);
    }

    long createDeleteIndexTask(long documentId) {
        jdbc.update(
                "INSERT INTO knowledge_index_task(document_id,operation,status,retry_count,"
                        + "next_retry_time,error_message,create_time,update_time)"
                        + " VALUES(?,'DELETE','PENDING',0,NOW(),NULL,NOW(),NOW())"
                        + " ON DUPLICATE KEY UPDATE status='PENDING',retry_count=0,"
                        + "next_retry_time=NOW(),error_message=NULL,update_time=NOW()",
                documentId);
        return jdbc.queryForObject(
                "SELECT id FROM knowledge_index_task WHERE document_id=? AND operation='DELETE'",
                Long.class,
                documentId);
    }

    Map<String, Object> indexTask(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM knowledge_index_task WHERE id=?", taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    List<Long> dueIndexTaskIds(int limit) {
        return jdbc.queryForList(
                "SELECT id FROM knowledge_index_task WHERE"
                        + " (status IN ('PENDING','RETRYING')"
                        + " AND (next_retry_time IS NULL OR next_retry_time<=NOW()))"
                        + " OR (status='PROCESSING'"
                        + " AND update_time<=DATE_SUB(NOW(),INTERVAL 5 MINUTE))"
                        + " ORDER BY id LIMIT ?",
                Long.class,
                limit);
    }

    int claimIndexTask(long taskId) {
        return jdbc.update(
                "UPDATE knowledge_index_task SET status='PROCESSING',update_time=NOW()"
                        + " WHERE id=? AND ((status IN ('PENDING','RETRYING')"
                        + " AND (next_retry_time IS NULL OR next_retry_time<=NOW()))"
                        + " OR (status='PROCESSING'"
                        + " AND update_time<=DATE_SUB(NOW(),INTERVAL 5 MINUTE)))",
                taskId);
    }

    void indexTaskSuccess(long taskId) {
        jdbc.update(
                "UPDATE knowledge_index_task SET status='SUCCESS',next_retry_time=NULL,"
                        + "error_message=NULL,update_time=NOW() WHERE id=?",
                taskId);
    }

    void indexTaskFailure(long taskId, String message, int maximumAttempts) {
        jdbc.update(
                "UPDATE knowledge_index_task SET retry_count=retry_count+1,"
                        + "status=IF(retry_count+1>=?,'FAILED','RETRYING'),"
                        + "next_retry_time=DATE_ADD(NOW(),INTERVAL POW(2,retry_count+1) SECOND),"
                        + "error_message=?,update_time=NOW() WHERE id=?",
                maximumAttempts,
                safeMessage(message),
                taskId);
    }

    void indexTaskObsolete(long taskId) {
        jdbc.update("UPDATE knowledge_index_task SET status='FAILED',next_retry_time=NULL,"
                + "error_message='文档已删除或版本失效，停止重试',update_time=NOW() WHERE id=?", taskId);
    }

    /**
     * 文档存储路径与原始名称。
     *
     * @author heyu
     * @since 2026/8/19
     */
    private static final class PathName {
        static String file(String path) {
            int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return path.substring(i + 1);
        }
    }

    private String safeMessage(String message) {
        String value = message == null || message.isBlank() ? "未知解析错误" : message;
        return value.substring(0, Math.min(value.length(), 1000));
    }

    private String metadataJson(StructuredChunk chunk) {
        try {
            return mapper.writeValueAsString(chunk.metadata());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("切片元数据无法序列化", exception);
        }
    }
}
