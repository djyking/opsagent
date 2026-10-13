package com.opsagent.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 Bearer Token 恢复当前请求的认证主体。
 *
 * @author heyu
 * @since 2026/7/19
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final VisitorSessionVerifier visitorSessions;
    private static final ObjectMapper JSON = new ObjectMapper();

    public JwtAuthenticationFilter(JwtService jwt, VisitorSessionVerifier visitorSessions) {
        this.jwt = jwt;
        this.visitorSessions = visitorSessions;
    }

    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // Internal controllers validate their own audience-bound service tokens.
        if (req.getRequestURI().startsWith("/internal/")) {
            chain.doFilter(req, res);
            return;
        }
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer "))
            try {
                OpsPrincipal p = jwt.parse(h.substring(7));
                boolean visitor = p.roles().contains("DEMO") || p.roles().contains("ROLE_DEMO");
                if (visitor && !DemoAccessPolicy.allows(req.getMethod(), req.getRequestURI())) {
                    res.setStatus(403);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter()
                            .write(
                                    "{\"code\":40300,\"message\":\"访客仅能操作自己的隔离演练和 AI"
                                            + " 会话\",\"data\":null}");
                    return;
                }
                if (visitor && !localAuthLeaseEndpoint(req)) visitorSessions.verify(p);
                var auths =
                        p.roles().stream()
                                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                                .map(SimpleGrantedAuthority::new)
                                .toList();
                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(p, null, auths));
            } catch (BusinessException failure) {
                SecurityContextHolder.clearContext();
                res.setStatus(
                        failure.getErrorCode() == ErrorCode.MIDDLEWARE_UNAVAILABLE ? 503 : 403);
                res.setContentType("application/json;charset=UTF-8");
                JSON.writeValue(
                        res.getWriter(),
                        ApiResponse.failure(failure.getErrorCode().code(), failure.getMessage()));
                return;
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        chain.doFilter(req, res);
    }

    private boolean localAuthLeaseEndpoint(HttpServletRequest request) {
        // Auth reads the same lease from its own database; its internal controller skips this
        // filter.
        return "GET".equals(request.getMethod()) && "/api/auth/me".equals(request.getRequestURI())
                || "POST".equals(request.getMethod())
                        && java.util.Set.of("/api/auth/logout", "/api/auth/end-experience")
                                .contains(request.getRequestURI());
    }
}
