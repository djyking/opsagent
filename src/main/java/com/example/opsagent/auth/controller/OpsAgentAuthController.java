package com.example.opsagent.auth.controller;

import com.example.opsagent.auth.dto.LoginRequest;
import com.example.opsagent.auth.dto.LoginResponse;
import com.example.opsagent.auth.service.AuthService;
import com.example.opsagent.auth.vo.AuthUserVO;
import com.example.opsagent.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class OpsAgentAuthController {

    private final AuthService authService;


    @PostMapping("/register")
    public ApiResponse register(@Validated @RequestBody LoginRequest request) {
        //加密并insert存入
        authService.register(request);
        return ApiResponse.success();
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Validated @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<AuthUserVO> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(authService.me(authorization));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(authorization);
        return ApiResponse.success();
    }
}
