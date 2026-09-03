package com.opsagent.common.security;

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
 * @since 2026/9/2
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer "))
            try {
                OpsPrincipal p = jwt.parse(h.substring(7));
                var auths =
                        p.roles().stream()
                                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                                .map(SimpleGrantedAuthority::new)
                                .toList();
                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(p, null, auths));
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        chain.doFilter(req, res);
    }
}
