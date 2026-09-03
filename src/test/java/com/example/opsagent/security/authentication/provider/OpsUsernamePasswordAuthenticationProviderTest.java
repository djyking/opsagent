package com.example.opsagent.security.authentication.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.opsagent.security.authentication.token.OpsAuthenticationToken;
import com.example.opsagent.security.authentication.user.OpsUserDetailsService;
import com.example.opsagent.security.authentication.user.OpsUserPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * 验证用户名密码 Provider 的认证状态转换和异常边界。
 *
 * @author heyu
 * @since 2026/8/15
 */
class OpsUsernamePasswordAuthenticationProviderTest {

    private OpsUserDetailsService userDetailsService;

    private PasswordEncoder passwordEncoder;

    private OpsUsernamePasswordAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        userDetailsService = mock(OpsUserDetailsService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        provider =
                new OpsUsernamePasswordAuthenticationProvider(userDetailsService, passwordEncoder);
    }

    @Test
    void shouldReturnAuthenticatedTokenAndEraseCredentialsForCorrectPassword() {
        OpsUserPrincipal principal = principal("enable");
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(principal);
        when(passwordEncoder.matches("correct", "encoded-password")).thenReturn(true);

        OpsAuthenticationToken request = OpsAuthenticationToken.unauthenticated("alice", "correct");
        OpsAuthenticationToken result = (OpsAuthenticationToken) provider.authenticate(request);

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isSameAs(principal);
        assertThat(result.getCredentials()).isNull();
    }

    @Test
    void shouldRejectWrongPassword() {
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(principal("enable"));
        when(passwordEncoder.matches("wrong", "encoded-password")).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                provider.authenticate(
                                        OpsAuthenticationToken.unauthenticated("alice", "wrong")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    void shouldHideWhetherUserExists() {
        when(userDetailsService.loadUserByUsername("missing"))
                .thenThrow(new UsernameNotFoundException("不存在"));

        assertThatThrownBy(
                        () ->
                                provider.authenticate(
                                        OpsAuthenticationToken.unauthenticated(
                                                "missing", "secret")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    void shouldRejectDisabledUser() {
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(principal("disable"));

        assertThatThrownBy(
                        () ->
                                provider.authenticate(
                                        OpsAuthenticationToken.unauthenticated("alice", "correct")))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void shouldOnlySupportOpsAuthenticationToken() {
        assertThat(provider.supports(OpsAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
    }

    private OpsUserPrincipal principal(String status) {
        return new OpsUserPrincipal(
                1L,
                "alice",
                "encoded-password",
                "Alice",
                status,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
