package com.opsagent.ticket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 工单领域服务启动入口。 */
@SpringBootApplication
@MapperScan("com.opsagent.ticket")
public class OpsTicketApplication {
    public static void main(String[] a) {
        SpringApplication.run(OpsTicketApplication.class, a);
    }
}
