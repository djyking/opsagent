package com.example.opsagent.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 承载登录或注册时提交的用户凭证。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Data
public class LoginRequest {

    @NotBlank
    @Size(max = 64)
    private String username;

    @NotBlank
    @Size(max = 72)
    private String password;
}
