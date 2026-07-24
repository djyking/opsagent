/*
 * <p>文件名称: OpsAuthConfiguration.java</p>
 * <p>项目描述: KOCA 金证云原生平台</p>
 * <p>公司名称: 深圳市金证科技股份有限公司</p>
 * <p>版权所有: (C) 2019-2023</p>
 */

package com.example.opsagent.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 *
 * 配置类
 * @author heyu 
 * @since 2026/7/18
 */
@EnableWebSecurity
@Configuration
public class OpsAuthConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }


}
