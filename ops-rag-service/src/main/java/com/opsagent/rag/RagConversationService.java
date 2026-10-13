package com.opsagent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 按登录账号隔离会话与问答记录；角色不授予读取他人私有会话的权限。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class RagConversationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    RagConversationService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 账号私有会话摘要。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Conversation(String id, String title, LocalDateTime createTime, LocalDateTime updateTime) {}

    /**
     * 会话列表分页。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record ConversationPage(List<Conversation> records, long total, int page, int pageSize) {}

    /**
     * 单轮问题、生成状态与持久化答案。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Turn(long id, String question, String answer, String status, String errorMessage,
                RagService.Answer result, LocalDateTime createTime) {}

    /**
     * 按游标读取的历史消息页。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record TurnPage(List<Turn> records, boolean hasMore) {}

    ConversationPage list(long userId, int page, int size) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_conversation WHERE user_id=? AND deleted=0", Long.class, userId);
        List<Conversation> rows = jdbc.query("""
                SELECT * FROM rag_conversation WHERE user_id=? AND deleted=0
                ORDER BY update_time DESC,id DESC LIMIT ? OFFSET ?
                """, this::conversation, userId, size, (long) (page - 1) * size);
        return new ConversationPage(rows, total == null ? 0 : total, page, size);
    }

    Conversation create(long userId, String title) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO rag_conversation(id,user_id,title) VALUES(?,?,?)", id, userId,
                title == null || title.isBlank() ? "新会话" : title.trim());
        return owned(id, userId);
    }

    Conversation owned(String id, long userId) {
        List<Conversation> rows = jdbc.query(
                "SELECT * FROM rag_conversation WHERE id=? AND user_id=? AND deleted=0",
                this::conversation, id, userId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在或不可访问");
        return rows.get(0);
    }

    @Transactional
    public Conversation rename(String id, long userId, String title) {
        lock(id, userId);
        jdbc.update("""
                UPDATE rag_conversation SET title=?,update_time=CURRENT_TIMESTAMP(3)
                WHERE id=? AND user_id=? AND deleted=0
                """, title.trim(), id, userId);
        return owned(id, userId);
    }

    @Transactional
    public void delete(String id, long userId) {
        lock(id, userId);
        jdbc.update("""
                UPDATE rag_conversation SET deleted=1,update_time=CURRENT_TIMESTAMP(3)
                WHERE id=? AND user_id=? AND deleted=0
                """, id, userId);
    }

    TurnPage turns(String id, long userId, Long beforeId) {
        owned(id, userId);
        List<Turn> rows = new ArrayList<>(jdbc.query("""
                SELECT t.* FROM rag_conversation_turn t JOIN rag_conversation c ON c.id=t.conversation_id
                WHERE c.id=? AND c.user_id=? AND c.deleted=0 AND t.id<? ORDER BY t.id DESC LIMIT 21
                """, this::turn, id, userId, beforeId == null ? Long.MAX_VALUE : beforeId));
        boolean more = rows.size() > 20;
        if (more) rows.remove(rows.size() - 1);
        Collections.reverse(rows);
        return new TurnPage(rows, more);
    }

    @Transactional
    public long begin(String id, long userId, String question) {
        lock(id, userId);
        jdbc.update("""
                UPDATE rag_conversation_turn SET status='INTERRUPTED',
                    error_message='上次生成中断，请重新提问',update_time=CURRENT_TIMESTAMP(3)
                WHERE conversation_id=? AND status='PROCESSING' AND create_time<?
                """, id, LocalDateTime.now().minusMinutes(15));
        Integer processing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rag_conversation_turn WHERE conversation_id=? AND status='PROCESSING'
                """, Integer.class, id);
        if (processing != null && processing > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "此会话仍在生成回答，请稍后刷新");
        }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO rag_conversation_turn(conversation_id,question,status) VALUES(?,?,'PROCESSING')
                    """, new String[] {"id"});
            statement.setString(1, id);
            statement.setString(2, question);
            return statement;
        }, key);
        jdbc.update("""
                UPDATE rag_conversation SET title=CASE WHEN title='新会话' THEN ? ELSE title END,
                    update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=? AND deleted=0
                """, question.substring(0, Math.min(80, question.length())), id, userId);
        Number generatedId = key.getKey();
        if (generatedId == null) throw new IllegalStateException("未能保存会话问题");
        return generatedId.longValue();
    }

    String context(String id, long userId) {
        owned(id, userId);
        List<Turn> recent = new ArrayList<>(jdbc.query("""
                SELECT t.* FROM rag_conversation_turn t JOIN rag_conversation c ON c.id=t.conversation_id
                WHERE c.id=? AND c.user_id=? AND c.deleted=0 AND t.status='COMPLETE'
                ORDER BY t.id DESC LIMIT 6
                """, this::turn, id, userId));
        Collections.reverse(recent);
        StringBuilder history = new StringBuilder();
        for (Turn turn : recent) {
            String answer = turn.answer() == null ? "" : turn.answer();
            history.append("用户：").append(turn.question()).append("\n助手：")
                    .append(answer, 0, Math.min(answer.length(), 3000)).append("\n\n");
        }
        return history.substring(Math.max(0, history.length() - 12000));
    }

    @Transactional
    public void complete(String id, long userId, long turnId, RagService.Answer answer) {
        lock(id, userId);
        String payload;
        try {
            payload = json.writeValueAsString(answer);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存问答结果", exception);
        }
        boolean incomplete = answer.metadata() != null && !answer.metadata().generationComplete();
        int updated = jdbc.update("""
                UPDATE rag_conversation_turn SET answer=?,result_json=?,status=?,error_message=?,
                    update_time=CURRENT_TIMESTAMP(3)
                WHERE id=? AND conversation_id=? AND status='PROCESSING'
                """, answer.answer(), payload, incomplete ? "INCOMPLETE" : "COMPLETE",
                incomplete ? "回答未完整生成，可重新提问" : null, turnId, id);
        if (updated == 1) {
            jdbc.update("""
                    UPDATE rag_conversation SET update_time=CURRENT_TIMESTAMP(3)
                    WHERE id=? AND user_id=? AND deleted=0
                    """, id, userId);
        }
    }

    void fail(String id, long userId, long turnId, String message) {
        jdbc.update("""
                UPDATE rag_conversation_turn SET status='INTERRUPTED',error_message=?,
                    update_time=CURRENT_TIMESTAMP(3)
                WHERE id=? AND conversation_id=? AND status='PROCESSING'
                  AND EXISTS(SELECT 1 FROM rag_conversation c WHERE c.id=? AND c.user_id=? AND c.deleted=0)
                """, message == null ? "生成中断，请重试" : message.substring(0, Math.min(500, message.length())),
                turnId, id, id, userId);
    }

    private void lock(String id, long userId) {
        List<String> rows = jdbc.query(
                "SELECT id FROM rag_conversation WHERE id=? AND user_id=? AND deleted=0 FOR UPDATE",
                (rs, row) -> rs.getString(1), id, userId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在或不可访问");
    }

    private Conversation conversation(ResultSet row, int index) throws SQLException {
        return new Conversation(row.getString("id"), row.getString("title"),
                row.getTimestamp("create_time").toLocalDateTime(), row.getTimestamp("update_time").toLocalDateTime());
    }

    private Turn turn(ResultSet row, int index) throws SQLException {
        String payload = row.getString("result_json");
        RagService.Answer result = null;
        if (payload != null) {
            try {
                result = json.readValue(payload, RagService.Answer.class);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("会话结果读取失败", exception);
            }
        }
        String status = row.getString("status");
        String error = row.getString("error_message");
        LocalDateTime created = row.getTimestamp("create_time").toLocalDateTime();
        if ("PROCESSING".equals(status) && created.isBefore(LocalDateTime.now().minusMinutes(15))) {
            status = "INTERRUPTED";
            error = "上次生成中断，请重新提问";
        }
        return new Turn(row.getLong("id"), row.getString("question"), row.getString("answer"),
                status, error, result, created);
    }
}
