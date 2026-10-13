package com.opsagent.ticket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 工单领域服务启动入口。
 *
 * @author heyu
 * @since 2026/8/7
 */
@SpringBootApplication
@MapperScan("com.opsagent.ticket")
@EnableScheduling
public class OpsTicketApplication {
    public static void main(String[] a) {
        SpringApplication.run(OpsTicketApplication.class, a);
    }
}
