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

    @Select(
            "SELECT user_id FROM auth_refresh_token WHERE token_hash=#{hash} AND revoked=0 AND"
                    + " expire_time>NOW() LIMIT 1")
    Long validUser(String hash);

    @Update(
            "UPDATE auth_refresh_token SET revoked=1,revoke_time=NOW() WHERE token_hash=#{hash} AND"
                    + " revoked=0")
    int revoke(String hash);
}
