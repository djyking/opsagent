package com.example.opsagent.security.current;

import java.util.List;

import com.example.opsagent.security.authentication.token.OpsAuthenticationToken;
import com.example.opsagent.security.authentication.user.OpsUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证业务模块只能从有效 SecurityContext 获取当前用户和角色。
 *
 * @author heyu
 * @since 2026/8/16
 */
class CurrentUserContextTest {

    private final CurrentUserContext currentUser = new CurrentUserContext();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReadAuthenticatedPrincipalAndRole() {
        OpsUserPrincipal principal = new OpsUserPrincipal(9L, "operator", "password", "Operator", "enable",
            List.of(new SimpleGrantedAuthority("ROLE_OPS")));
        SecurityContextHolder.getContext().setAuthentication(OpsAuthenticationToken.authenticated(principal));

        assertThat(currentUser.userId()).isEqualTo(9L);
        assertThat(currentUser.username()).isEqualTo("operator");
        assertThat(currentUser.hasRole("OPS")).isTrue();
        assertThat(currentUser.hasRole("ADMIN")).isFalse();
    }

    @Test
    void shouldRejectMissingAuthentication() {
        assertThatThrownBy(currentUser::requirePrincipal)
            .isInstanceOf(InsufficientAuthenticationException.class);
    }
}
