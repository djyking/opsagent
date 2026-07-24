package com.example.opsagent.auth.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.auth.entity.SysUser;
import com.example.opsagent.auth.dao.SysUserDao;
import com.example.opsagent.auth.service.SysUserService;
import org.springframework.stereotype.Service;

@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserDao, SysUser> implements SysUserService {
}
