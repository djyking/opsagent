package com.opsagent.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/** 检索增强问答服务启动入口。 */
@SpringBootApplication
@EnableFeignClients
public class OpsRagApplication {
    public static void main(String[] a) {
        SpringApplication.run(OpsRagApplication.class, a);
    }
}
