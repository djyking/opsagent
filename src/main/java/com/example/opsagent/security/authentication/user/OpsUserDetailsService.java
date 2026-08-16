package com.example.opsagent.security.authentication.user;

import java.util.List;
import java.util.Locale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsagent.auth.dao.SysRoleDao;
import com.example.opsagent.auth.dao.SysUserDao;
import com.example.opsagent.auth.entity.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 从现有用户表加载用户并转换为安全认证主体。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Service
@RequiredArgsConstructor
public class OpsUserDetailsService implements UserDetailsService {

    private static final String ROLE_PREFIX = "ROLE_";

    private final SysUserDao sysUserDao;

    private final SysRoleDao sysRoleDao;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserDao.selectOne(new LambdaQueryWrapper<SysUser>()
            .eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        List<SimpleGrantedAuthority> authorities = sysRoleDao.selectEnabledRoleCodesByUserId(user.getId()).stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .map(code -> code.toUpperCase(Locale.ROOT))
            .map(code -> code.startsWith(ROLE_PREFIX) ? code : ROLE_PREFIX + code)
            .distinct()
            .map(SimpleGrantedAuthority::new)
            .toList();
        return new OpsUserPrincipal(user.getId(), user.getUsername(), user.getPassword(), user.getDisplayName(),
            user.getStatus(), authorities);
    }
}
