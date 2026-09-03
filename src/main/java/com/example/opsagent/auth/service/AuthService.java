package com.example.opsagent.auth.service;

import com.example.opsagent.auth.dto.RegisterRequest;

/**
 * 提供认证模块保留的用户注册业务能力。
 *
 * @author heyu
 * @since 2026/8/15
 */
public interface AuthService {

    void register(RegisterRequest request);
}
