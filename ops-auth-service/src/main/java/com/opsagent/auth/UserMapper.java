package com.opsagent.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用户、角色和权限关联查询。
 *
 * @author heyu
 * @since 2026/8/6
 */
public interface UserMapper extends BaseMapper<User> {
    @Select("SELECT id FROM sys_role WHERE code='USER' AND status='enable' AND deleted=0")
    Long registrationRoleId();

    @Insert("INSERT INTO sys_user_role(user_id,role_id) VALUES(#{userId},#{roleId})")
    int assignRegistrationRole(@Param("userId") long userId, @Param("roleId") long roleId);

    @Select(
            "SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE"
                    + " ur.user_id=#{userId} AND r.status='enable' AND r.deleted=0")
    List<String> roles(long userId);

    @Select(
            "SELECT p.code FROM sys_permission p JOIN sys_role_permission rp ON"
                    + " rp.permission_id=p.id JOIN sys_user_role ur ON ur.role_id=rp.role_id WHERE"
                    + " ur.user_id=#{userId} AND p.status='enable'")
    List<String> permissions(long userId);
}
