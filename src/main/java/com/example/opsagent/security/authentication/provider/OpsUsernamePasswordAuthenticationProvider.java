package com.example.opsagent.security.authentication.provider;

import com.example.opsagent.security.authentication.token.OpsAuthenticationToken;
import com.example.opsagent.security.authentication.user.OpsUserDetailsService;
import com.example.opsagent.security.authentication.user.OpsUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 编排用户名密码用户加载、状态检查和 BCrypt 密码校验。
 *
 * @author heyu
 * @since 2026/8/15
 */
@RequiredArgsConstructor
public class OpsUsernamePasswordAuthenticationProvider implements OpsAuthenticationProvider {

    private final OpsUserDetailsService userDetailsService;

    private final PasswordEncoder passwordEncoder;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());
        OpsUserPrincipal principal;
        try {
            principal = (OpsUserPrincipal) userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException exception) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        if (!principal.isEnabled()) {
            throw new DisabledException("用户已禁用");
        }
        boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(password, principal.getPassword());
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        if (!passwordMatches) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        // 密码校验成功后创建全新的已认证 Token，原始密码不再向后传递。
        OpsAuthenticationToken result = OpsAuthenticationToken.authenticated(principal);
        result.setDetails(authentication.getDetails());
        result.eraseCredentials();
        return result;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OpsAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
