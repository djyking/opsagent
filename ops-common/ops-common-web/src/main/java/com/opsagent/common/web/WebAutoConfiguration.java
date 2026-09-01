package com.opsagent.common.web;
import org.springframework.boot.autoconfigure.AutoConfiguration;import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;import org.springframework.context.annotation.Bean;
@AutoConfiguration @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET) public class WebAutoConfiguration {@Bean TraceIdFilter traceIdFilter(){return new TraceIdFilter();}@Bean GlobalExceptionHandler globalExceptionHandler(){return new GlobalExceptionHandler();}}
