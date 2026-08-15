package com.example.opsagent.security.authentication.user;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsagent.auth.dao.SysUserDao;
import com.example.opsagent.auth.entity.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 从现有用户表加载用户并转换为安全认证主体。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Service
@RequiredArgsConstructor
public class OpsUserDetailsService implements UserDetailsService {

    private final SysUserDao sysUserDao;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserDao.selectOne(new LambdaQueryWrapper<SysUser>()
            .eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        // 当前数据库没有角色关系表，先为有效用户提供最小角色。
        return new OpsUserPrincipal(user.getId(), user.getUsername(), user.getPassword(), user.getDisplayName(),
            user.getStatus(), List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
