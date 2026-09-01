package com.example.opsagent.auth.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsagent.auth.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 持久化用户与角色的关联关系。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Mapper
public interface SysUserRoleDao extends BaseMapper<SysUserRole> {
}
