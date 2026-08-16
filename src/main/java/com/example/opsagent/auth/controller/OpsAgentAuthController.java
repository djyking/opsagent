package com.example.opsagent.auth.controller;

import com.example.opsagent.auth.dto.RegisterRequest;
import com.example.opsagent.auth.service.AuthService;
import com.example.opsagent.auth.vo.AuthUserVO;
import com.example.opsagent.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import com.example.opsagent.security.authentication.user.OpsUserPrincipal;
import com.example.opsagent.security.config.OpsSecurityProperties;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供公开注册和基于 SecurityContext 的当前用户查询接口。
 *
 * @author heyu
 * @since 2026/8/15
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class OpsAgentAuthController {

    private final AuthService authService;

    private final OpsSecurityProperties securityProperties;

    @GetMapping("/login")
    public ResponseEntity<Void> loginPage() {
        return ResponseEntity.status(HttpStatus.FOUND)
            .header("Location", securityProperties.getLoginPageUrl())
            .build();
    }

    @PostMapping("/register")
    public ApiResponse<Void> register(@Validated @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.success();
    }

    @GetMapping("/me")
    public ApiResponse<AuthUserVO> me(@AuthenticationPrincipal OpsUserPrincipal principal) {
        return ApiResponse.success(AuthUserVO.from(principal));
    }
}
