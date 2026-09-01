package com.example.opsagent.auth.dao;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsagent.auth.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 查询角色以及用户当前有效的角色编码。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Mapper
public interface SysRoleDao extends BaseMapper<SysRole> {

    @Select("""
        SELECT r.code
        FROM sys_role r
        INNER JOIN sys_user_role ur ON ur.role_id = r.id
        WHERE ur.user_id = #{userId}
          AND r.status = 'enable'
          AND r.deleted = 0
        ORDER BY r.id
        """)
    List<String> selectEnabledRoleCodesByUserId(@Param("userId") Long userId);

    @Select("""
        SELECT id
        FROM sys_role
        WHERE code = #{code}
          AND status = 'enable'
          AND deleted = 0
        LIMIT 1
        """)
    Long selectEnabledRoleIdByCode(@Param("code") String code);
}
