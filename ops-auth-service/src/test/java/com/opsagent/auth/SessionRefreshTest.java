package com.opsagent.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.JwtProperties;
import com.opsagent.common.security.JwtService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 限定检查活动、绝对期限和刷新令牌单次使用，使用本地假账号。
 *
 * @author heyu
 * @since 2026/9/3
 */
class SessionRefreshTest {
    private final UserMapper users = mock(UserMapper.class);
    private final RefreshTokenMapper tokens = mock(RefreshTokenMapper.class);
    private final AuthService service =
            new AuthService(
                    users, tokens, mock(PasswordEncoder.class), jwt(), mock(CaptchaService.class));

    private static JwtService jwt() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-only-session-refresh-signing-key-at-least-32-bytes");
        return new JwtService(properties);
    }

    private RefreshTokenMapper.RefreshLease lease(Instant activity, Instant absolute) {
        return new RefreshTokenMapper.RefreshLease(
                42L,
                false,
                local(absolute),
                local(absolute.minusSeconds(86400)),
                "same-session",
                local(absolute.minusSeconds(86400)),
                local(activity),
                local(absolute));
    }

    private static LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private void activeUser() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("session-test");
        when(user.getStatus()).thenReturn("enable");
        when(user.getDeleted()).thenReturn(0);
        when(users.selectById(42L)).thenReturn(user);
        when(users.roles(42L)).thenReturn(List.of("ADMIN"));
        when(tokens.revoke(anyString())).thenReturn(1);
    }

    @Test
    void backgroundRefreshDoesNotResetIdleClock() {
        Instant activity = Instant.now().minusSeconds(3600);
        Instant absolute = Instant.now().plusSeconds(12 * 3600);
        when(tokens.lockLease(anyString())).thenReturn(lease(activity, absolute));
        activeUser();
        var result = service.refresh(new AuthDtos.RefreshRequest("test-only-token"));
        assertThat(result.lastActivityAt()).isEqualTo(activity);
        assertThat(result.idleExpiresAt()).isEqualTo(activity.plusSeconds(7200));
        assertThat(result.sessionExpiresAt()).isEqualTo(absolute);
        assertThat(result.sessionId()).isEqualTo("same-session");
        assertThat(result.expiresAt()).isBefore(Instant.now().plusSeconds(1801));
    }

    @Test
    void humanActivityExtendsIdleButNeverAbsoluteDeadline() {
        Instant now = Instant.now();
        Instant absolute = now.plusSeconds(300);
        when(tokens.lockLease(anyString())).thenReturn(lease(now.minusSeconds(7000), absolute));
        activeUser();
        var result = service.refresh(new AuthDtos.RefreshRequest("test-only-token", now));
        assertThat(result.lastActivityAt()).isEqualTo(now);
        assertThat(result.sessionExpiresAt()).isEqualTo(absolute);
        assertThat(result.expiresAt()).isBeforeOrEqualTo(absolute.plusMillis(5));
        ArgumentCaptor<LocalDateTime> expiry = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tokens)
                .insertSession(
                        anyString(),
                        eq(42L),
                        anyString(),
                        expiry.capture(),
                        any(),
                        eq("same-session"),
                        any(),
                        any(),
                        eq(local(absolute)));
        assertThat(expiry.getValue()).isEqualTo(local(absolute));
    }

    @Test
    void staleSessionCannotBeRevivedByNewActivity() {
        Instant now = Instant.now();
        when(tokens.lockLease(anyString()))
                .thenReturn(lease(now.minusSeconds(7201), now.plusSeconds(3600)));
        assertThatThrownBy(() -> service.refresh(new AuthDtos.RefreshRequest("test", now)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 小时");
        verify(tokens, never()).revoke(anyString());
        verifyNoInteractions(users);
    }

    @Test
    void losingAtomicRotationCannotMintAnotherToken() {
        Instant now = Instant.now();
        when(tokens.lockLease(anyString())).thenReturn(lease(now, now.plusSeconds(3600)));
        activeUser();
        when(tokens.revoke(anyString())).thenReturn(0);
        assertThatThrownBy(() -> service.refresh(new AuthDtos.RefreshRequest("test")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("凭据已更新");
        verify(tokens, never())
                .insertSession(
                        anyString(),
                        anyLong(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void upgradingLegacyTokenKeepsItsOriginalLoginTimeAndBindsThePredecessor() {
        Instant now = Instant.now();
        Instant started = now.minusSeconds(3600);
        when(tokens.lockLease(anyString()))
                .thenReturn(
                        new RefreshTokenMapper.RefreshLease(
                                42L,
                                false,
                                local(now.plusSeconds(86400)),
                                local(started),
                                null,
                                null,
                                null,
                                null));
        activeUser();
        when(tokens.attachLegacySession(anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        var result = service.refresh(new AuthDtos.RefreshRequest("legacy-test", now));
        assertThat(result.sessionStartedAt()).isEqualTo(started);
        assertThat(result.sessionExpiresAt()).isEqualTo(started.plusSeconds(86400));
        verify(tokens)
                .attachLegacySession(
                        anyString(),
                        eq(result.sessionId()),
                        eq(local(started)),
                        eq(local(now)),
                        eq(local(started.plusSeconds(86400))));
    }

    @Test
    void absoluteExpiryAndFutureActivityAreRejected() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> SessionLifetime.activity(now, now, now, now, Duration.ofHours(2)))
                .hasMessageContaining("24 小时");
        assertThatThrownBy(
                        () ->
                                SessionLifetime.activity(
                                        now,
                                        now.plusSeconds(120),
                                        now.plusSeconds(86400),
                                        now,
                                        Duration.ofHours(2)))
                .hasMessageContaining("活动时间无效");
    }
}
