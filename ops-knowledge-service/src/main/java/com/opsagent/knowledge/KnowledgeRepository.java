package com.opsagent.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;

import java.sql.*;
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

    KnowledgeRepository(JdbcTemplate j) {
        jdbc = j;
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

    long addDocument(long base, FileStorageService.StoredFile f, long user) {
        KeyHolder k = new GeneratedKeyHolder();
        jdbc.update(
                c -> {
                    PreparedStatement p =
                            c.prepareStatement(
                                    "INSERT INTO knowledge_document(knowledge_base_id, file_name,"
                                        + " original_name, file_type, file_size, storage_path,"
                                        + " status, content_hash, version, create_by, create_time,"
                                        + " update_time, deleted)"
                                        + " VALUES(?,?,?,?,?,?,'UPLOADED',?,1,?,NOW(),NOW(),0)",
                                    Statement.RETURN_GENERATED_KEYS);
                    p.setLong(1, base);
                    p.setString(2, PathName.file(f.relativePath()));
                    p.setString(3, f.originalName());
                    p.setString(4, f.extension());
                    p.setLong(5, f.size());
                    p.setString(6, f.relativePath());
                    p.setString(7, f.sha256());
                    p.setLong(8, user);
                    return p;
                },
                k);
        return Objects.requireNonNull(k.getKey()).longValue();
    }

    List<Map<String, Object>> documents(long base) {
        return jdbc.queryForList(
                "SELECT id, knowledge_base_id, original_name, file_type, file_size, status,"
                        + " content_hash, parse_error, create_by, create_time, update_time FROM"
                        + " knowledge_document WHERE knowledge_base_id=? AND deleted=0 ORDER BY id"
                        + " DESC",
                base);
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

    void parsed(long id, List<String> chunks) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE document_id=?", id);
        for (int i = 0; i < chunks.size(); i++)
            jdbc.update(
                    "INSERT INTO"
                        + " knowledge_chunk(document_id,chunk_index,content,token_count,embedding_status,create_time)"
                        + " VALUES(?,?,?,?, 'PENDING',NOW())",
                    id,
                    i,
                    chunks.get(i),
                    Math.max(1, chunks.get(i).length() / 4));
        jdbc.update(
                "UPDATE knowledge_document SET status='PARSED',parse_error=NULL WHERE id=?", id);
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
                    + " id,document_id,chunk_index,content,token_count,embedding_status,page_number,create_time"
                    + " FROM knowledge_chunk WHERE document_id=? ORDER BY chunk_index",
                id);
    }

    List<Map<String, Object>> search(String query, int limit) {
        String like = "%" + query + "%";
        return jdbc.queryForList(
                "SELECT c.id chunkId,c.document_id documentId,c.chunk_index"
                    + " chunkIndex,c.content,d.original_name documentName FROM knowledge_chunk c"
                    + " JOIN knowledge_document d ON d.id=c.document_id WHERE c.content LIKE ? AND"
                    + " d.deleted=0 ORDER BY c.id DESC LIMIT ?",
                like,
                limit);
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
}
