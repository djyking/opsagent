package com.opsagent.common.web;
import java.io.IOException;import java.util.UUID;import jakarta.servlet.FilterChain;import jakarta.servlet.ServletException;import jakarta.servlet.http.HttpServletRequest;import jakarta.servlet.http.HttpServletResponse;import org.slf4j.MDC;import org.springframework.web.filter.OncePerRequestFilter;
public class TraceIdFilter extends OncePerRequestFilter {public static final String HEADER="X-Trace-Id";
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{String id=req.getHeader(HEADER);if(id==null||id.isBlank())id=UUID.randomUUID().toString().replace("-","");MDC.put("traceId",id);res.setHeader(HEADER,id);try{chain.doFilter(req,res);}finally{MDC.remove("traceId");}}}
