package com.example.opsagent.security.jwt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void shouldRejectNonPositiveExpirationAtInitialization() {
        OpsTokenProperties properties = new OpsTokenProperties();
        properties.setSecret("ops-agent-test-secret-that-is-at-least-32-bytes-long");
        properties.setExpireMinutes(0);

        assertThatThrownBy(() -> new OpsTokenService(properties).validateConfiguration())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expire-minutes 必须大于 0");
    }

    @Test
    void shouldTreatMalformedTokenAsNotUsable() {
        OpsTokenProperties properties = new OpsTokenProperties();
        properties.setSecret("ops-agent-test-secret-that-is-at-least-32-bytes-long");

        assertThat(new OpsTokenService(properties).isExpired("not-a-jwt")).isTrue();
    }
}
