package com.example.opsagent.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 接口文档配置。
 *
 * @author heyu
 * @since 2026/7/15
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI opsAgentOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("OpsAgent API")
                                .description("OpsAgent phase 1 API")
                                .version("v1"));
    }
}
