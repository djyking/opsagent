package com.example.opsagent.security.authentication.manager;

import java.util.List;

import com.example.opsagent.security.authentication.provider.OpsAuthenticationProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * 根据 supports 结果将认证请求路由到对应 opsAgent Provider。
 *
 * @author heyu
 * @since 2026/8/15
 */
@RequiredArgsConstructor
public class OpsAuthenticationManager implements AuthenticationManager {

    private final List<OpsAuthenticationProvider> providers;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        for (OpsAuthenticationProvider provider : providers) {
            // Manager 只负责 Provider 路由，不加载用户也不校验密码。
            if (provider.supports(authentication.getClass())) {
                return provider.authenticate(authentication);
            }
        }
        throw new ProviderNotFoundException("没有 Provider 支持 " + authentication.getClass().getName());
    }
}
