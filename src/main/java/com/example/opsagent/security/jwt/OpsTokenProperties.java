package com.example.opsagent.security.jwt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 密钥与有效期配置。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Data
@ConfigurationProperties(prefix = "ops-agent.security.jwt")
public class OpsTokenProperties {

    private String secret;

    private long expireMinutes = 120;

    public long getExpiresInSeconds() {
        return expireMinutes * 60;
    }
}
