package com.example.opsagent.auth.vo;

import java.util.List;

import com.example.opsagent.security.authentication.user.OpsUserPrincipal;
import lombok.Data;

/**
 * 返回当前认证用户的公开身份与权限信息。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Data
public class AuthUserVO {

    private Long userId;

    private String username;

    private String displayName;

    private List<String> authorities;

    public static AuthUserVO from(OpsUserPrincipal principal) {
        AuthUserVO result = new AuthUserVO();
        result.setUserId(principal.getUserId());
        result.setUsername(principal.getUsername());
        result.setDisplayName(principal.getDisplayName());
        result.setAuthorities(principal.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .toList());
        return result;
    }
}
