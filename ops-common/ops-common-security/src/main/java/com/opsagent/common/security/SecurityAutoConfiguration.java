package com.opsagent.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.*;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 为 Servlet 业务服务提供统一的无状态 JWT 安全链。
 *
 * @author heyu
 * @since 2026/7/22
 */
@AutoConfiguration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(MethodSecurityExceptionHandler.class)
    MethodSecurityExceptionHandler methodSecurityExceptionHandler() {
        return new MethodSecurityExceptionHandler();
    }

    @Bean
    JwtService jwtService(JwtProperties p) {
        return new JwtService(p);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean(UserDetailsService.class)
    UserDetailsService opsNoopUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    @Bean
    SecurityFilterChain opsSecurityFilterChain(
            HttpSecurity http, JwtService jwt, JwtProperties p, ObjectMapper mapper)
            throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (req, res, x) -> {
                                                    res.setStatus(401);
                                                    res.setContentType(
                                                            "application/json;charset=UTF-8");
                                                    mapper.writeValue(
                                                            res.getWriter(),
                                                            ApiResponse.failure(
                                                                    ErrorCode.UNAUTHENTICATED
                                                                            .code(),
                                                                    "未登录或登录已过期"));
                                                })
                                        .accessDeniedHandler(
                                                (req, res, x) -> {
                                                    res.setStatus(403);
                                                    res.setContentType(
                                                            "application/json;charset=UTF-8");
                                                    mapper.writeValue(
                                                            res.getWriter(),
                                                            ApiResponse.failure(
                                                                    ErrorCode.FORBIDDEN.code(),
                                                                    "无权访问该资源"));
                                                }))
                .authorizeHttpRequests(
                        a ->
                                a.dispatcherTypeMatchers(DispatcherType.ASYNC)
                                        .permitAll()
                                        .requestMatchers(p.getPermitAll().toArray(String[]::new))
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwt),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
