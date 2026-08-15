package com.example.opsagent.security.config;

import java.util.List;

import com.example.opsagent.security.authentication.filter.OpsLoginAuthenticationFilter;
import com.example.opsagent.security.authentication.handler.OpsAuthenticationFailureHandler;
import com.example.opsagent.security.authentication.handler.OpsAuthenticationSuccessHandler;
import com.example.opsagent.security.authentication.manager.OpsAuthenticationManager;
import com.example.opsagent.security.authentication.provider.OpsUsernamePasswordAuthenticationProvider;
import com.example.opsagent.security.authentication.user.OpsUserDetailsService;
import com.example.opsagent.security.handler.OpsAccessDeniedHandler;
import com.example.opsagent.security.handler.OpsAuthenticationEntryPoint;
import com.example.opsagent.security.jwt.OpsJwtAuthenticationFilter;
import com.example.opsagent.security.jwt.OpsTokenProperties;
import com.example.opsagent.security.jwt.OpsTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 配置无状态 REST 登录、JWT 认证、白名单及 401/403 处理链。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({OpsSecurityProperties.class, OpsTokenProperties.class})
public class OpsSecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public OpsAuthenticationManager opsAuthenticationManager(OpsUserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder) {
        OpsUsernamePasswordAuthenticationProvider provider =
            new OpsUsernamePasswordAuthenticationProvider(userDetailsService, passwordEncoder);
        return new OpsAuthenticationManager(List.of(provider));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, OpsAuthenticationManager authenticationManager,
        ObjectMapper objectMapper, OpsAuthenticationSuccessHandler successHandler,
        OpsAuthenticationFailureHandler failureHandler, OpsTokenService tokenService,
        OpsUserDetailsService userDetailsService, OpsAuthenticationEntryPoint authenticationEntryPoint,
        OpsAccessDeniedHandler accessDeniedHandler, OpsSecurityProperties securityProperties) throws Exception {
        OpsLoginAuthenticationFilter loginFilter = new OpsLoginAuthenticationFilter(authenticationManager, objectMapper);
        loginFilter.setAuthenticationSuccessHandler(successHandler);
        loginFilter.setAuthenticationFailureHandler(failureHandler);

        OpsJwtAuthenticationFilter jwtFilter = new OpsJwtAuthenticationFilter(tokenService, userDetailsService,
            authenticationEntryPoint, securityProperties);

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(securityProperties.getPermitAll().toArray(String[]::new)).permitAll()
                .anyRequest().authenticated())
            // JWT 必须先恢复上下文，登录 Filter 随后只处理登录路径，最后才进入授权判断。
            .addFilterBefore(loginFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, OpsLoginAuthenticationFilter.class);

        return http.build();
    }
}
