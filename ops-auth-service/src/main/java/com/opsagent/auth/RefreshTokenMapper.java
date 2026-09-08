package com.opsagent.auth;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

/**
 * Refresh Token 持久化操作，数据库中只保存不可逆摘要。
 *
 * @author heyu
 * @since 2026/8/4
 */
public interface RefreshTokenMapper {
    @Insert(
            "INSERT INTO"
                + " auth_refresh_token(token_id,user_id,token_hash,expire_time,revoked,create_time)"
                + " VALUES(#{id},#{userId},#{hash},#{expires},0,NOW())")
    int insert(String id, long userId, String hash, LocalDateTime expires);

    @Insert(
            "INSERT INTO auth_refresh_token(token_id,user_id,token_hash,expire_time,revoked,"
                + "create_time,session_id,session_started_at,last_activity_at,absolute_expires_at)"
                + " VALUES(#{id},#{userId},#{hash},#{expires},0,#{now},#{sessionId},#{started},"
                + "#{activity},#{absolute})")
    int insertSession(
            String id,
            long userId,
            String hash,
            LocalDateTime expires,
            LocalDateTime now,
            String sessionId,
            LocalDateTime started,
            LocalDateTime activity,
            LocalDateTime absolute);

    @Select(
            "SELECT user_id,revoked,expire_time,create_time,session_id,session_started_at,"
                    + "last_activity_at,absolute_expires_at FROM auth_refresh_token "
                    + "WHERE token_hash=#{hash} LIMIT 1 FOR UPDATE")
    RefreshLease lockLease(String hash);

    @Update(
            "UPDATE auth_refresh_token SET session_id=#{sessionId},session_started_at=#{started},"
                    + "last_activity_at=#{activity},absolute_expires_at=#{absolute} "
                    + "WHERE token_hash=#{hash} AND session_id IS NULL AND revoked=0")
    int attachLegacySession(
            String hash,
            String sessionId,
            LocalDateTime started,
            LocalDateTime activity,
            LocalDateTime absolute);

    @Update(
            "UPDATE auth_refresh_token SET revoked=1,revoke_time=NOW() "
                    + "WHERE session_id=#{sessionId} AND revoked=0")
    int revokeSession(String sessionId);

    /**
     * 同一登录会话的刷新租约，时间使用认证库的本地时区。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record RefreshLease(
            long userId,
            boolean revoked,
            LocalDateTime expireTime,
            LocalDateTime createTime,
            String sessionId,
            LocalDateTime sessionStartedAt,
            LocalDateTime lastActivityAt,
            LocalDateTime absoluteExpiresAt) {}

    @Select(
            "SELECT user_id FROM auth_refresh_token WHERE token_hash=#{hash} AND revoked=0 AND"
                    + " expire_time>NOW() LIMIT 1")
    Long validUser(String hash);

    @Update(
            "UPDATE auth_refresh_token SET revoked=1,revoke_time=NOW() WHERE token_hash=#{hash} AND"
                    + " revoked=0")
    int revoke(String hash);
}
