package com.opsagent.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.JwtService;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;

/**
 * 异步身份复核拒绝已过期、撤销及跨用户伪造的运行主体。
 *
 * @author heyu
 * @since 2026/9/3
 */
class InternalActorLeaseTest {
    private final UserMapper users = mock(UserMapper.class);
    private final AuthService service =
            new AuthService(
                    users,
                    mock(RefreshTokenMapper.class),
                    mock(PasswordEncoder.class),
                    mock(JwtService.class),
                    mock(CaptchaService.class));

    @Test
    void leaseMustExistRemainUnrevokedAndNotExpire() {
        ReflectionTestUtils.setField(service, "demoEnabled", true);
        assertThat(service.actor(-10).active()).isFalse();
        when(users.visitor(-10))
                .thenReturn(
                        new UserMapper.VisitorLease(
                                -10,
                                "访客",
                                LocalDateTime.ofInstant(
                                        Instant.now().plusSeconds(300), ZoneOffset.UTC),
                                false));
        assertThat(service.actor(-10).active()).isTrue();
        assertThat(service.actor(-10).roles()).containsExactly("DEMO");
        when(users.visitor(-10))
                .thenReturn(
                        new UserMapper.VisitorLease(
                                -10,
                                "访客",
                                LocalDateTime.ofInstant(
                                        Instant.now().minusSeconds(1), ZoneOffset.UTC),
                                false));
        assertThat(service.actor(-10).active()).isFalse();
        when(users.visitor(-10))
                .thenReturn(
                        new UserMapper.VisitorLease(
                                -10,
                                "访客",
                                LocalDateTime.ofInstant(
                                        Instant.now().plusSeconds(300), ZoneOffset.UTC),
                                true));
        assertThat(service.actor(-10).active()).isFalse();
    }

    @Test
    void internalActorLookupBindsAudienceAndExactActor() {
        String secret = "test-internal-actor-lease-secret-at-least-32-bytes";
        InternalActorTokens tokens = new InternalActorTokens(secret);
        InternalActorController controller = new InternalActorController(service, secret);
        var context =
                new InternalActorTokens.Context(
                        -10,
                        "访客",
                        List.of("DEMO"),
                        "run-1",
                        "ops-demo-order-service",
                        Instant.now().plusSeconds(90));
        assertThatThrownBy(() -> controller.actor("Bearer " + tokens.issue("ticket", context), -10))
                .hasMessageContaining("INTERNAL");
        assertThatThrownBy(() -> controller.actor("Bearer " + tokens.issue("auth", context), -11))
                .hasMessageContaining("不一致");
        verifyNoInteractions(users);
    }

    @Test
    void currentDatabaseRolesReplaceStaleRoleSnapshot() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn("enable");
        when(user.getDeleted()).thenReturn(0);
        when(user.getUsername()).thenReturn("ops");
        when(users.selectById(2L)).thenReturn(user);
        when(users.roles(2L)).thenReturn(List.of("USER"));
        assertThat(service.actor(2L).roles()).containsExactly("USER");
        assertThat(service.actor(2L).expiresAt()).isNull();
        when(user.getStatus()).thenReturn("disable");
        assertThat(service.actor(2L).active()).isFalse();
        assertThat(service.actor(2L).roles()).isEmpty();
    }
}
