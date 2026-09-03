package com.opsagent.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 密钥、有效期和匿名访问路径配置。
 *
 * @author heyu
 * @since 2026/9/2
 */
@ConfigurationProperties("ops.security")
public class JwtProperties {
    private String secret = "";
    private Duration accessTokenTtl = Duration.ofMinutes(30);
    private List<String> permitAll = new ArrayList<>(List.of("/actuator/health", "/actuator/info"));

    public String getSecret() {
        return secret;
    }

    public void setSecret(String v) {
        secret = v;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration v) {
        accessTokenTtl = v;
    }

    public List<String> getPermitAll() {
        return permitAll;
    }

    public void setPermitAll(List<String> v) {
        permitAll = v;
    }
}
