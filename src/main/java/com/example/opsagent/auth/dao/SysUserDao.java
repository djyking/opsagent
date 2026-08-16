package com.example.opsagent.auth.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsagent.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 持久化系统用户数据。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Mapper
public interface SysUserDao extends BaseMapper<SysUser> {

}
