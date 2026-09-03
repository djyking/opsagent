package com.example.opsagent.security.jwt;

import com.example.opsagent.security.authentication.token.OpsAuthenticationToken;
import com.example.opsagent.security.authentication.user.OpsUserDetailsService;
import com.example.opsagent.security.authentication.user.OpsUserPrincipal;
import com.example.opsagent.security.config.OpsSecurityProperties;
import com.example.opsagent.security.handler.OpsAuthenticationEntryPoint;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 校验 Bearer JWT 并为当前请求恢复 SecurityContext 认证身份。
 *
 * @author heyu
 * @since 2026/8/15
 */
public class OpsJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final OpsTokenService tokenService;

    private final OpsUserDetailsService userDetailsService;

    private final OpsAuthenticationEntryPoint authenticationEntryPoint;

    private final RequestMatcher permitAllMatcher;

    public OpsJwtAuthenticationFilter(
            OpsTokenService tokenService,
            OpsUserDetailsService userDetailsService,
            OpsAuthenticationEntryPoint authenticationEntryPoint,
            OpsSecurityProperties securityProperties) {
        this.tokenService = tokenService;
        this.userDetailsService = userDetailsService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        List<RequestMatcher> matchers =
                securityProperties.getPermitAll().stream()
                        .map(
                                pattern ->
                                        (RequestMatcher)
                                                PathPatternRequestMatcher.withDefaults()
                                                        .matcher(pattern))
                        .toList();
        this.permitAllMatcher = new OrRequestMatcher(matchers);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return permitAllMatcher.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String tokenValue = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!tokenService.validateToken(tokenValue)) {
                throw new BadCredentialsException("JWT 无效或已过期");
            }
            Claims claims = tokenService.extractTokenClaims(tokenValue);
            OpsUserPrincipal principal =
                    (OpsUserPrincipal)
                            userDetailsService.loadUserByUsername(
                                    tokenService.extractUsername(claims));
            if (!principal.isEnabled()
                    || !principal.getUserId().equals(tokenService.extractUserId(claims))) {
                throw new BadCredentialsException("JWT 对应用户无效");
            }

            // JWT 可信后恢复 Authentication，再显式放入当前请求的 SecurityContext。
            Authentication authentication = OpsAuthenticationToken.authenticated(principal);
            ((OpsAuthenticationToken) authentication)
                    .setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (AuthenticationException | IllegalArgumentException | JwtException exception) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                    request, response, new BadCredentialsException("JWT 认证失败", exception));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
