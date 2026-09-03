package com.example.opsagent.security.authentication.provider;

import org.springframework.security.authentication.AuthenticationProvider;

/**
 * 限定 opsAgent 自定义认证管理器可路由的认证提供者。
 *
 * @author heyu
 * @since 2026/8/15
 */
public interface OpsAuthenticationProvider extends AuthenticationProvider {}
