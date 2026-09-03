package com.example.opsagent.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 承载新用户注册信息。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Data
public class RegisterRequest {

    @NotBlank
    @Size(max = 64)
    private String username;

    @NotBlank
    @Size(min = 6, max = 72)
    private String password;

    @Size(max = 64)
    private String displayName;
}
