package com.example.opsagent.security.current;

import com.example.opsagent.security.authentication.user.OpsUserPrincipal;

import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 统一从 SecurityContext 获取当前认证用户及其角色。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Component
public class CurrentUserContext {

    public OpsUserPrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OpsUserPrincipal principal)) {
            throw new InsufficientAuthenticationException("当前请求没有有效认证用户");
        }
        return principal;
    }

    public Long userId() {
        return requirePrincipal().getUserId();
    }

    public String username() {
        return requirePrincipal().getUsername();
    }

    public boolean hasRole(String role) {
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return requirePrincipal().getAuthorities().stream()
                .anyMatch(grantedAuthority -> authority.equals(grantedAuthority.getAuthority()));
    }
}
