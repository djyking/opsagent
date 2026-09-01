package com.example.opsagent.auth.service;

import com.example.opsagent.auth.dao.SysRoleDao;
import com.example.opsagent.auth.dao.SysUserDao;
import com.example.opsagent.auth.dao.SysUserRoleDao;
import com.example.opsagent.auth.dto.RegisterRequest;
import com.example.opsagent.auth.entity.SysUser;
import com.example.opsagent.auth.entity.SysUserRole;
import com.example.opsagent.auth.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证注册用户的密码入库与默认角色绑定。
 *
 * @author heyu
 * @since 2026/8/16
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private SysUserDao sysUserDao;

    @Mock
    private SysRoleDao sysRoleDao;

    @Mock
    private SysUserRoleDao sysUserRoleDao;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldBindDefaultUserRoleAfterRegistration() {
        AuthServiceImpl service = new AuthServiceImpl(sysUserDao, sysRoleDao, sysUserRoleDao, passwordEncoder);
        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("correct-password");
        request.setDisplayName("Alice");

        when(sysUserDao.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode("correct-password")).thenReturn("bcrypt-hash");
        when(sysUserDao.insert(any(SysUser.class))).thenAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(10L);
            return 1;
        });
        when(sysRoleDao.selectEnabledRoleIdByCode("USER")).thenReturn(20L);

        service.register(request);

        ArgumentCaptor<SysUserRole> relationCaptor = ArgumentCaptor.forClass(SysUserRole.class);
        verify(sysUserRoleDao).insert(relationCaptor.capture());
        assertThat(relationCaptor.getValue().getUserId()).isEqualTo(10L);
        assertThat(relationCaptor.getValue().getRoleId()).isEqualTo(20L);
    }
}
