/*
 * <p>文件名称: AuthUserDetailService.java</p>
 * <p>项目描述: KOCA 金证云原生平台</p>
 * <p>公司名称: 深圳市金证科技股份有限公司</p>
 * <p>版权所有: (C) 2019-2023</p>
 */

package com.example.opsagent.auth.verify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsagent.auth.dao.SysUserDao;
import com.example.opsagent.auth.entity.SysUser;
import com.example.opsagent.auth.enums.AuthErrorCode;
import com.example.opsagent.common.enums.AuthRegisterStatusEnum;
import com.example.opsagent.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 实现UserDetaiService，以供{@link org.springframework.security.authentication.dao.DaoAuthenticationProvider}调用
 *
 * @author heyu 
 * @since 2026/7/22
 */
@Service
@RequiredArgsConstructor
public class AuthUserDetailService implements UserDetailsService {

    private final SysUserDao sysUserDao;

    /**
     * {@link org.springframework.security.authentication.dao.DaoAuthenticationProvider} 这里会验证密码
     * 实现类不用验证密码，也无需被外部调用。
     * @param username the username identifying the user whose data is required.
     * @return
     * @throws UsernameNotFoundException
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = new SysUser(username);
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SysUser::getUsername, user.getUsername());
        user = sysUserDao.selectOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(AuthErrorCode.USER_NOT_EXIST);
        }

        boolean status = !user.getStatus().equals(AuthRegisterStatusEnum.ENABLE.getGetCode()) ? true : false;

        //创建userDetails给provider使用
        return User.withUsername(username).
            password(user.getPassword()).
            disabled(status).
            authorities("ROLE_USER").
            build();
    }
}
