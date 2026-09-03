package com.example.opsagent.security.authentication.user;

import com.example.opsagent.common.enums.AuthRegisterStatusEnum;
import com.fasterxml.jackson.annotation.JsonIgnore;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 将系统用户适配为 Spring Security 使用的认证主体。
 *
 * @author heyu
 * @since 2026/8/15
 */
public class OpsUserPrincipal implements UserDetails {

    private final Long userId;

    private final String username;

    @JsonIgnore private final String password;

    private final String displayName;

    private final String status;

    private final List<GrantedAuthority> authorities;

    public OpsUserPrincipal(
            Long userId,
            String username,
            String password,
            String displayName,
            String status,
            Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.status = status;
        this.authorities = List.copyOf(authorities);
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    @JsonIgnore
    public String getPassword() {
        return password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStatus() {
        return status;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isEnabled() {
        return AuthRegisterStatusEnum.isEnabled(status);
    }
}
