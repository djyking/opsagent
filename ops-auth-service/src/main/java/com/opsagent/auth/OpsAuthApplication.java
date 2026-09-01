package com.opsagent.auth;
import org.mybatis.spring.annotation.MapperScan;import org.springframework.boot.SpringApplication;import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication @MapperScan("com.opsagent.auth") public class OpsAuthApplication {public static void main(String[] args){SpringApplication.run(OpsAuthApplication.class,args);}}
