package com.opsagent.auth;
import java.time.Instant;import java.util.List;import jakarta.validation.constraints.*;
final class AuthDtos {private AuthDtos(){}record LoginRequest(@NotBlank String username,@NotBlank String password){}record RefreshRequest(@NotBlank String refreshToken){}record TokenResponse(String accessToken,String refreshToken,String tokenType,Instant expiresAt){}record CurrentUser(long userId,String username,List<String> roles,List<String> permissions){}}
