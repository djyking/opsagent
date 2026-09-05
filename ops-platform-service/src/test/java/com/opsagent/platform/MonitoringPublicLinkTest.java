package com.opsagent.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证没有公网监控入口时不构造相对路径，同时保留本地监控跳转。
 *
 * @author heyu
 * @since 2026/9/3
 */
class MonitoringPublicLinkTest {
    @Test
    void shouldKeepUnpublishedTargetsEmptyAndPreserveConfiguredLinks() {
        assertThat(MonitoringService.publicLink("", "/targets")).isEmpty();
        assertThat(MonitoringService.publicLink("  ", "/d/overview")).isEmpty();
        assertThat(MonitoringService.publicLink("http://localhost:9090/", "/targets"))
                .isEqualTo("http://localhost:9090/targets");
    }
}
