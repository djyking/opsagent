package com.example.opsagent.opsagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan({
        "com.example.opsagent.opsagent.ticket.mapper",
        "com.example.opsagent.opsagent.knowledgebase.mapper",
        "com.example.opsagent.opsagent.document.mapper"
})
@SpringBootApplication
public class OpsagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsagentApplication.class, args);
    }

}
