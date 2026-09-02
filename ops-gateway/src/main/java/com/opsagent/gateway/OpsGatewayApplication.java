package com.opsagent.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** OpsAgent 统一 API 网关启动入口。 */
@SpringBootApplication
public class OpsGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsGatewayApplication.class, args);
    }
}
