package com.example.opsagent.security.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Security 白名单配置。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Data
@ConfigurationProperties(prefix = "ops-agent.security")
public class OpsSecurityProperties {

    private List<String> permitAll = new ArrayList<>();
}
