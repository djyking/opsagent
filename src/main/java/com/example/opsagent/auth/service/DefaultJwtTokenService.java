/*
 * <p>文件名称: DefaultJwtTokenService.java</p>
 * <p>项目描述: KOCA 金证云原生平台</p>
 * <p>公司名称: 深圳市金证科技股份有限公司</p>
 * <p>版权所有: (C) 2019-2023</p>
 */

package com.example.opsagent.auth.service;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 *
 *
 * @author heyu 
 * @since 2026-07-23
 */
@Service
public class DefaultJwtTokenService implements JwtTokenService {

    @Override
    public String generateToken(Authentication authentication) {
        return "test-token";
    }

    @Override
    public long getExpiresIn() {
        return 3600;
    }
}
