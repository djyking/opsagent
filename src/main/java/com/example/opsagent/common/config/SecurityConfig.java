/*
 * <p>文件名称: SecurityConfig.java</p>
 * <p>项目描述: KOCA 金证云原生平台</p>
 * <p>公司名称: 深圳市金证科技股份有限公司</p>
 * <p>版权所有: (C) 2019-2023</p>
 */

package com.example.opsagent.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * springSecurity安全配置
 *
 * @author heyu 
 * @since 2026/7/22
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     *
     * @param httpSecurity 实现SecurityFilterChain的bean，标准配置。
     * @param authenticationProvider 由springbean自动注入
     * @return
     * @throws Exception
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity,
        DaoAuthenticationProvider authenticationProvider) throws Exception {
        httpSecurity.
            csrf(csrf -> csrf.disable()).
            sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).
            authenticationProvider(authenticationProvider).
            authorizeHttpRequests(
                authorize -> authorize.
                    requestMatchers("/api/auth/login").permitAll().
                    anyRequest().authenticated()).
            formLogin(form -> form.disable()).
            httpBasic(basic -> basic.disable());

        return httpSecurity.build();
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(DaoAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }
    
}
