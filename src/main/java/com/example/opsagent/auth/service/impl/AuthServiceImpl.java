package com.example.opsagent.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsagent.auth.dao.SysUserDao;
import com.example.opsagent.auth.dto.RegisterRequest;
import com.example.opsagent.auth.entity.SysUser;
import com.example.opsagent.auth.enums.AuthErrorCode;
import com.example.opsagent.auth.service.AuthService;
import com.example.opsagent.common.enums.AuthRegisterStatusEnum;
import com.example.opsagent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 实现用户注册及 BCrypt 密码入库逻辑。
 *
 * @author heyu
 * @since 2026/8/15
 */
@RequiredArgsConstructor
@Service
public class AuthServiceImpl implements AuthService {

    private final SysUserDao sysUserDao;

    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        if (querySysUserOne(request) != null) {
            throw new BusinessException(AuthErrorCode.USER_ALREADY_EXIST);
        }
        SysUser user = new SysUser();
        user.setUsername(request.getUsername().trim());
        if (request.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码的 UTF-8 编码长度不能超过 72 字节");
        }
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setStatus(AuthRegisterStatusEnum.ENABLE.getCode());
        user.setDeleted(0);
        if (StringUtils.hasText(request.getDisplayName())) {
            user.setDisplayName(request.getDisplayName().trim());
        } else {
            user.setDisplayName(request.getUsername().trim());
        }
        try {
            sysUserDao.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(AuthErrorCode.USER_ALREADY_EXIST);
        }
    }
    private SysUser querySysUserOne(RegisterRequest request) {
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUsername, request.getUsername().trim());
        return sysUserDao.selectOne(queryWrapper);
    }

}
