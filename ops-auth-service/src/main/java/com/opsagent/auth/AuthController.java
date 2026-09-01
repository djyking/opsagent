package com.opsagent.auth;
import static com.opsagent.auth.AuthDtos.*;import com.opsagent.common.core.ApiResponse;import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/auth") public class AuthController {private final AuthService service;AuthController(AuthService service){this.service=service;}
 @PostMapping("/login") ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest r){return ApiResponse.success(service.login(r));}
 @PostMapping("/refresh") ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest r){return ApiResponse.success(service.refresh(r));}
 @PostMapping("/logout") ApiResponse<Void> logout(@RequestBody(required=false) RefreshRequest r){service.logout(r==null?null:r.refreshToken());return ApiResponse.success();}
 @GetMapping("/me") ApiResponse<CurrentUser> me(){return ApiResponse.success(service.current());}}
