package com.opsagent.auth;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.JwtService;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 验证关闭注册时没有数据库写入，以及禁用账号不能借刷新令牌恢复登录。
 *
 * @author heyu
 * @since 2026/9/3
 */
class PublicAuthProtectionTest {
    private final UserMapper users = mock(UserMapper.class);
    private final RefreshTokenMapper tokens = mock(RefreshTokenMapper.class);
    private final JwtService jwt = mock(JwtService.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AuthService service = new AuthService(users, tokens, passwords, jwt, mock(CaptchaService.class));

    @Test
    void shouldRejectRegistrationBeforeAccessingAccountsWhenDisabled() {
        assertThat(service.registrationEnabled()).isTrue();
        ReflectionTestUtils.setField(service, "registrationEnabled", false);
        assertThat(service.registrationEnabled()).isFalse();
        assertThatThrownBy(() -> service.register(new AuthDtos.RegisterRequest("demo", "password", "演示")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未开放注册");
        verifyNoInteractions(users, passwords, jwt, tokens);
    }

    @Test
    void shouldNotIssueAnyTokensForDisabledOrDeletedUsers() {
        when(tokens.validUser(anyString())).thenReturn(10L);
        User user = mock(User.class);
        when(users.selectById(10L)).thenReturn(user);
        when(user.getStatus()).thenReturn("disable");
        when(user.getDeleted()).thenReturn(0);
        assertThatThrownBy(() -> service.refresh(new AuthDtos.RefreshRequest("test-refresh")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("账号不可用");
        when(user.getStatus()).thenReturn("enable");
        when(user.getDeleted()).thenReturn(1);
        assertThatThrownBy(() -> service.refresh(new AuthDtos.RefreshRequest("test-refresh")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("账号不可用");
        verifyNoInteractions(jwt);
        verify(users, never()).roles(10L);
    }
}
