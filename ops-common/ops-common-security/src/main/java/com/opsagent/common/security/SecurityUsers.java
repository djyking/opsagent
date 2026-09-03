package com.opsagent.common.security;

import com.opsagent.common.core.*;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文访问工具，统一取得经过认证的 OpsAgent 用户。
 *
 * @author heyu
 * @since 2026/9/2
 */
public final class SecurityUsers {
    private SecurityUsers() {}

    public static OpsPrincipal current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof OpsPrincipal p))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "未登录或登录已过期");
        return p;
    }
}
