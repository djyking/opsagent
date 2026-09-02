package com.opsagent.auth;

import static com.opsagent.auth.AuthDtos.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsagent.common.core.*;
import com.opsagent.common.security.*;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 认证领域服务，负责凭据校验和 Access/Refresh Token 生命周期。 */
@Service
public class AuthService {
    private final UserMapper users;
    private final RefreshTokenMapper refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    AuthService(
            UserMapper users,
            RefreshTokenMapper refreshTokens,
            PasswordEncoder encoder,
            JwtService jwt) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @Transactional
    TokenResponse login(LoginRequest req) {
        User u = find(req.username());
        if (u == null
                || !"enable".equalsIgnoreCase(u.getStatus())
                || !encoder.matches(req.password(), u.getPassword()))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "用户名或密码错误");
        return issue(u);
    }

    @Transactional
    TokenResponse refresh(RefreshRequest req) {
        // Refresh Token 仅以 SHA-256 摘要落库，避免数据库泄露后直接重放原始令牌。
        String hash = hash(req.refreshToken());
        Long userId = refreshTokens.validUser(hash);
        if (userId == null)
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Refresh Token 无效或已过期");
        refreshTokens.revoke(hash);
        User u = users.selectById(userId);
        if (u == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED, "用户不存在");
        return issue(u);
    }

    @Transactional
    void logout(String token) {
        if (token != null && !token.isBlank()) refreshTokens.revoke(hash(token));
    }

    CurrentUser current() {
        OpsPrincipal p = SecurityUsers.current();
        return new CurrentUser(p.userId(), p.username(), p.roles(), users.permissions(p.userId()));
    }

    private TokenResponse issue(User u) {
        List<String> roles = users.roles(u.getId());
        IssuedToken access = jwt.issue(u.getId(), u.getUsername(), roles);
        // 两段随机 UUID 提供足够熵，数据库只持久化其摘要。
        String raw = UUID.randomUUID() + "." + UUID.randomUUID();
        refreshTokens.insert(
                UUID.randomUUID().toString(),
                u.getId(),
                hash(raw),
                LocalDateTime.now().plusDays(7));
        return new TokenResponse(access.token(), raw, "Bearer", access.expiresAt());
    }

    private User find(String username) {
        return users.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username.trim())
                        .eq(User::getDeleted, 0));
    }

    private String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
