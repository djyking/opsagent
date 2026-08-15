package com.example.opsagent.security.jwt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 JWT 密钥配置在应用初始化阶段即可发现错误。
 *
 * @author heyu
 * @since 2026/8/15
 */
class OpsTokenServiceConfigurationTest {

    @Test
    void shouldRejectMissingOrShortSecretAtInitialization() {
        OpsTokenProperties properties = new OpsTokenProperties();
        properties.setSecret("too-short");

        assertThatThrownBy(() -> new OpsTokenService(properties).validateConfiguration())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("至少包含 32 个 UTF-8 字节");
    }
}
