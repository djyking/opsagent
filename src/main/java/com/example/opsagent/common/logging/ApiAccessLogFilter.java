package com.example.opsagent.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 在完整过滤器链外记录 API 最终状态、耗时和链路标识，不读取请求体或认证凭据。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiAccessLogFilter extends OncePerRequestFilter {

    private static final String TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = normalizedTraceId(request.getHeader("X-Trace-Id"));
        response.setHeader("X-Trace-Id", traceId);
        long start = System.nanoTime();
        try (MDC.MDCCloseable ignored = MDC.putCloseable(TRACE_ID, traceId)) {
            filterChain.doFilter(request, response);
        } finally {
            long costTimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.info(
                    "API method={} path={} status={} costTimeMs={} traceId={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    costTimeMs,
                    traceId);
        }
    }

    private String normalizedTraceId(String candidate) {
        if (candidate != null && candidate.matches("[A-Za-z0-9_-]{8,64}")) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
