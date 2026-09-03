package com.example.opsagent.security.authentication.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.opsagent.security.authentication.provider.OpsAuthenticationProvider;
import com.example.opsagent.security.authentication.token.OpsAuthenticationToken;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.core.Authentication;

import java.util.List;

/**
 * 验证自定义 Manager 按 supports 路由 Provider 的行为。
 *
 * @author heyu
 * @since 2026/8/15
 */
class OpsAuthenticationManagerTest {

    @Test
    void shouldSkipUnsupportedProviderAndReturnFirstSuccessfulResult() {
        OpsAuthenticationProvider unsupported = mock(OpsAuthenticationProvider.class);
        OpsAuthenticationProvider supported = mock(OpsAuthenticationProvider.class);
        OpsAuthenticationProvider later = mock(OpsAuthenticationProvider.class);
        Authentication request = OpsAuthenticationToken.unauthenticated("alice", "secret");
        Authentication result = mock(Authentication.class);
        when(unsupported.supports(request.getClass())).thenReturn(false);
        when(supported.supports(request.getClass())).thenReturn(true);
        when(supported.authenticate(request)).thenReturn(result);

        Authentication actual =
                new OpsAuthenticationManager(List.of(unsupported, supported, later))
                        .authenticate(request);

        assertThat(actual).isSameAs(result);
        verify(unsupported).supports(request.getClass());
        verify(unsupported, never()).authenticate(request);
        verify(supported).authenticate(request);
        verify(later, never()).supports(request.getClass());
    }

    @Test
    void shouldThrowWhenNoProviderSupportsToken() {
        OpsAuthenticationProvider provider = mock(OpsAuthenticationProvider.class);
        Authentication request = OpsAuthenticationToken.unauthenticated("alice", "secret");
        when(provider.supports(request.getClass())).thenReturn(false);

        assertThatThrownBy(
                        () -> new OpsAuthenticationManager(List.of(provider)).authenticate(request))
                .isInstanceOf(ProviderNotFoundException.class);
    }

    @Test
    void shouldNotSwallowProviderAuthenticationException() {
        OpsAuthenticationProvider provider = mock(OpsAuthenticationProvider.class);
        Authentication request = OpsAuthenticationToken.unauthenticated("alice", "wrong");
        when(provider.supports(request.getClass())).thenReturn(true);
        when(provider.authenticate(request))
                .thenThrow(new BadCredentialsException("bad credentials"));

        assertThatThrownBy(
                        () -> new OpsAuthenticationManager(List.of(provider)).authenticate(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("bad credentials");
    }
}
