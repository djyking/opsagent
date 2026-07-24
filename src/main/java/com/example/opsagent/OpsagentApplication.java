package com.example.opsagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.example.opsagent.**.dao")
@SpringBootApplication
public class OpsagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsagentApplication.class, args);
    }

}
