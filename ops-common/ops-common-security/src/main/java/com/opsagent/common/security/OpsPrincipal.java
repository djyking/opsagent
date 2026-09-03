package com.opsagent.common.security;

import java.security.Principal;
import java.util.List;

/**
 * OpsAgent 认证主体，保存用户标识、令牌标识和角色快照。
 *
 * @author heyu
 * @since 2026/9/2
 */
public record OpsPrincipal(long userId, String username, String tokenId, List<String> roles)
        implements Principal {
    public OpsPrincipal {
        roles = List.copyOf(roles);
    }

    @Override
    public String getName() {
        return username;
    }
}
