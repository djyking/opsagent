package com.example.opsagent.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 这里可以用 {record} 16新特性去实现DTO，
 * 但是比起class没有大升级，只是为了解决重复代码的问题
 * 还是用class和lombok
 */
@Data
public class LoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    private String displayName;
}
