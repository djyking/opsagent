package com.opsagent.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Gateway 的 JWT 密钥和匿名访问路径配置。
 *
 * @author heyu
 * @since 2026/9/2
 */
@ConfigurationProperties("ops.gateway.security")
public class GatewaySecurityProperties {

    private String jwtSecret = "";

    private List<String> publicPaths =
            List.of("/api/auth/login", "/api/auth/refresh", "/actuator/health");

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String v) {
        jwtSecret = v;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> v) {
        publicPaths = v;
    }
}
