package com.opsagent.auth;

import static com.opsagent.auth.AuthDtos.*;

import com.opsagent.common.core.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;

/**
 * 提供登录、令牌刷新、退出和当前用户查询接口。
 *
 * @author heyu
 * @since 2026/7/31
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    private final CaptchaService captcha;

    AuthController(AuthService service, CaptchaService captcha) {
        this.service = service;
        this.captcha = captcha;
    }

    @GetMapping("/captcha")
    ApiResponse<CaptchaResponse> captcha(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return ApiResponse.success(captcha.issue(request.getRemoteAddr()));
    }

    @GetMapping("/features")
    ApiResponse<java.util.Map<String, Boolean>> features(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.success(
                java.util.Map.of(
                        "registrationEnabled",
                        service.registrationEnabled(),
                        "demoEnabled",
                        service.demoEnabled()));
    }

    @PostMapping("/login")
    ApiResponse<TokenResponse> login(
            @Valid @RequestBody LoginRequest r,
            @CookieValue(name = "opsagent_experience", required = false) String experience,
            HttpServletRequest request,
            HttpServletResponse response) {
        var result = service.loginWithExperience(r, experience);
        response.setHeader("Cache-Control", "no-store");
        if (result.credential() != null) {
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    experienceCookie(
                                    result.credential(),
                                    Duration.between(Instant.now(), result.experienceExpiresAt()),
                                    request)
                            .toString());
        }
        return ApiResponse.success(result.tokens());
    }

    @PostMapping("/end-experience")
    ApiResponse<Void> endExperience(HttpServletRequest request, HttpServletResponse response) {
        service.endExperience();
        response.addHeader(
                HttpHeaders.SET_COOKIE, experienceCookie("", Duration.ZERO, request).toString());
        return ApiResponse.success();
    }

    private ResponseCookie experienceCookie(
            String value, Duration age, HttpServletRequest request) {
        return ResponseCookie.from("opsagent_experience", value)
                .httpOnly(true)
                .secure(
                        request.isSecure()
                                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")))
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(age.isNegative() ? Duration.ZERO : age)
                .build();
    }

    @PostMapping("/register")
    ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        service.register(request);
        return ApiResponse.success();
    }

    @PostMapping("/refresh")
    ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest r) {
        return ApiResponse.success(service.refresh(r));
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(@RequestBody(required = false) RefreshRequest r) {
        service.logout(r == null ? null : r.refreshToken());
        return ApiResponse.success();
    }

    @GetMapping("/me")
    ApiResponse<CurrentUser> me() {
        return ApiResponse.success(service.current());
    }
}
