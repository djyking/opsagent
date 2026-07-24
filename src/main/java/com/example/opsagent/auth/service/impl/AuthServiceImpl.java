package com.example.opsagent.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsagent.auth.dao.SysUserDao;
import com.example.opsagent.auth.dto.LoginRequest;
import com.example.opsagent.auth.dto.LoginResponse;
import com.example.opsagent.auth.entity.SysUser;
import com.example.opsagent.auth.enums.AuthErrorCode;
import com.example.opsagent.auth.service.AuthService;
import com.example.opsagent.auth.service.JwtTokenService;
import com.example.opsagent.auth.vo.AuthUserVO;
import com.example.opsagent.common.enums.AuthRegisterStatusEnum;
import com.example.opsagent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class AuthServiceImpl implements AuthService {

    private final SysUserDao sysUserDao;

    private final PasswordEncoder passwordEncoder;

    private final AuthenticationManager manager;

    private final JwtTokenService jwtTokenService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(LoginRequest request) {
        if (querySysUserOne(request) != null) {
            throw new BusinessException(AuthErrorCode.USER_ALREADY_EXIST);
        }
        SysUser user = new SysUser(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setStatus(AuthRegisterStatusEnum.ENABLE.getGetCode());
        user.setDeleted(0);
        if (!StringUtils.isBlank(request.getDisplayName())) {
            user.setDisplayName(request.getDisplayName());
        } else {
            user.setDisplayName(request.getUsername());
        }
        sysUserDao.insert(user);
    }


    /**
     * Authentication创建认证请求对象，验证之后并登录
     * @param request
     * @return
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        Authentication authentication =
            UsernamePasswordAuthenticationToken.unauthenticated(request.getUsername(), request.getPassword());
        Authentication resultAuth = manager.authenticate(authentication);

        String accessToken = jwtTokenService.generateToken(resultAuth);
        return new LoginResponse(accessToken, "Bearer", 3600);
    }

    @Override
    public AuthUserVO me(String authorization) {
        AuthUserVO vo = new AuthUserVO();
        vo.setToken(authorization);
        return vo;
    }

    @Override
    public void logout(String authorization) {
        // Token invalidation will be implemented with Redis.
    }

    /**
     * true存在，false不存在
     * @param request
     * @return
     */
    private SysUser querySysUserOne(LoginRequest request) {
        SysUser user = new SysUser(request.getUsername());
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUsername, user.getUsername());
        return sysUserDao.selectOne(queryWrapper);
    }

    /**
     * 引入jwt进行token创建
     * @param user
     * @return
     */
    private String buildToken(SysUser user) {
        //这里先留白
        return "token创建成功";
    }
}
