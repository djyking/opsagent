package com.opsagent.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 Gateway 安全属性绑定。
 *
 * @author heyu
 * @since 2026/8/31
 */
@Configuration
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class GatewaySecurityConfiguration {}
