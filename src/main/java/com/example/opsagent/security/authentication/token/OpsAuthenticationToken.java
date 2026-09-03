package com.example.opsagent.security.authentication.token;

import com.example.opsagent.security.authentication.user.OpsUserPrincipal;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * 明确表示认证前凭证与认证后用户身份的认证令牌。
 *
 * @author heyu
 * @since 2026/8/15
 */
public final class OpsAuthenticationToken extends AbstractAuthenticationToken {

    private final Object principal;

    private Object credentials;

    private OpsAuthenticationToken(String username, String password) {
        super(null);
        this.principal = username;
        this.credentials = password;
        super.setAuthenticated(false);
    }

    private OpsAuthenticationToken(OpsUserPrincipal principal) {
        super(principal.getAuthorities());
        this.principal = principal;
        this.credentials = null;
        super.setAuthenticated(true);
    }

    public static OpsAuthenticationToken unauthenticated(String username, String password) {
        return new OpsAuthenticationToken(username, password);
    }

    public static OpsAuthenticationToken authenticated(OpsUserPrincipal principal) {
        return new OpsAuthenticationToken(principal);
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        credentials = null;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            throw new IllegalArgumentException("不能直接将未认证令牌提升为已认证状态");
        }
        super.setAuthenticated(false);
    }
}
