package com.opsagent.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 平台管理服务启动入口。
 *
 * @author heyu
 * @since 2026/8/28
 */
@SpringBootApplication
public class OpsPlatformApplication {
    public static void main(String[] a) {
        SpringApplication.run(OpsPlatformApplication.class, a);
    }
}
