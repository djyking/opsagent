package com.opsagent.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 仅服务隔离订单演示的真实业务目标，无正式业务数据库和容器控制权限。
 *
 * @author heyu
 * @since 2026/9/3
 */
@SpringBootApplication
@EnableScheduling
public class DemoOrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoOrderApplication.class, args);
    }
}
