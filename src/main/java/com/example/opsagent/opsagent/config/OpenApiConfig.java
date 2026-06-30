package com.example.opsagent.opsagent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI opsagentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("opsagent API")
                        .description("Enterprise intelligent operations platform practice API")
                        .version("v1"));
    }
}
