package com.opsagent.rag;

import feign.RequestInterceptor;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.*;

/**
 * 将当前请求的认证信息和 Trace ID 透传给知识服务。
 *
 * @author heyu
 * @since 2026/8/23
 */
@Configuration
public class FeignSecurityConfiguration {
    @Bean
    RequestInterceptor tokenRelay() {
        return t -> {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes a) {
                HttpServletRequest r = a.getRequest();
                String auth = r.getHeader("Authorization");
                String trace = r.getHeader("X-Trace-Id");
                if (auth != null) t.header("Authorization", auth);
                if (trace != null) t.header("X-Trace-Id", trace);
            }
        };
    }
}
