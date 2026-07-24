package com.example.opsagent.auth.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsagent.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserDao extends BaseMapper<SysUser> {

}
