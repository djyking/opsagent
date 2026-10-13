package com.opsagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.JwtProperties;
import com.opsagent.common.security.JwtService;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

/**
 * Public visitor credentials never become a persistent privileged account.
 *
 * @author heyu
 * @since 2026/9/3
 */
class DemoLoginTest {
    private final UserMapper users = mock(UserMapper.class);
    private final RefreshTokenMapper tokens = mock(RefreshTokenMapper.class);
    private final CaptchaService captcha = mock(CaptchaService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    @Test
    void createsIsolatedShortLivedVisitorsWithoutChangingAccounts() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-for-visitor-token-signatures-only-2026");
        JwtService jwt = new JwtService(properties);
        AuthService service = new AuthService(users, tokens, encoder, jwt, captcha);
        ReflectionTestUtils.setField(service, "demoEnabled", true);
        var request = new AuthDtos.LoginRequest("user", "user", "captcha", "ABCDE");
        var first = service.login(request);
        var second = service.login(request);
        var identity = jwt.parse(first.accessToken());
        assertThat(identity.roles()).containsExactly("DEMO");
        assertThat(identity.userId())
                .isNegative()
                .isNotEqualTo(jwt.parse(second.accessToken()).userId());
        assertThat(first.refreshToken()).isEmpty();
        assertThat(Duration.between(Instant.now(), first.expiresAt()).toMinutes())
                .isBetween(29L, 30L);
        verify(captcha, times(2)).verify("captcha", "ABCDE");
        verify(users, times(2)).createVisitor(anyLong(), any());
        verify(users, never()).insert(any(User.class));
        verifyNoInteractions(tokens, encoder);
    }

    @Test
    void featureIsOffByDefaultAndRequiresCaptcha() {
        AuthService service =
                new AuthService(users, tokens, encoder, mock(JwtService.class), captcha);
        assertThat(service.demoEnabled()).isFalse();
        assertThatThrownBy(
                        () ->
                                service.login(
                                        new AuthDtos.LoginRequest("user", "user", "id", "wrong")))
                .isInstanceOf(BusinessException.class);
        ReflectionTestUtils.setField(service, "demoEnabled", true);
        doThrow(new IllegalArgumentException("验证码错误")).when(captcha).verify("id", "wrong");
        assertThatThrownBy(
                        () ->
                                service.login(
                                        new AuthDtos.LoginRequest("user", "user", "id", "wrong")))
                .hasMessageContaining("验证码错误");
    }
}
