package com.opsagent.auth;

import static com.opsagent.auth.AuthDtos.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsagent.common.core.*;
import com.opsagent.common.security.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * 认证领域服务，负责凭据校验和 Access/Refresh Token 生命周期。
 *
 * @author heyu
 * @since 2026/8/2
 */
@Service
public class AuthService {
    private final UserMapper users;
    private final RefreshTokenMapper refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final CaptchaService captcha;

    @Value("${ops.auth.registration-enabled:true}")
    private boolean registrationEnabled = true;

    @Value("${ops.auth.demo-enabled:false}")
    private boolean demoEnabled;

    AuthService(
            UserMapper users,
            RefreshTokenMapper refreshTokens,
            PasswordEncoder encoder,
            JwtService jwt,
            CaptchaService captcha) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.captcha = captcha;
    }

    @Transactional
    TokenResponse login(LoginRequest req) {
        captcha.verify(req.captchaId(), req.captchaCode());
        if (demoEnabled && "user".equals(req.username()) && "user".equals(req.password())) {
            // Each visitor gets a separate identity; a public credential must not share chat
            // history.
            long visitorId = -new java.security.SecureRandom().nextLong(1, 9_007_199_254_740_991L);
            IssuedToken access =
                    jwt.issue(visitorId, "访客", List.of("DEMO"), Duration.ofMinutes(30));
            users.createVisitor(
                    visitorId, LocalDateTime.ofInstant(access.expiresAt(), ZoneOffset.UTC));
            return new TokenResponse(access.token(), "", "Bearer", access.expiresAt());
        }
        User u = find(req.username());
        if (u == null
                || !"enable".equalsIgnoreCase(u.getStatus())
                || !encoder.matches(req.password(), u.getPassword()))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "用户名或密码错误");
        return issue(u);
    }

    @Transactional
    void register(RegisterRequest request) {
        if (!registrationEnabled) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前环境未开放注册，请使用已分配的账号登录");
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(ErrorCode.VALIDATION, "密码的 UTF-8 编码不能超过 72 字节");
        }
        String username = request.username().trim();
        if (find(username) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }
        Long roleId = users.registrationRoleId();
        if (roleId == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "普通用户角色暂不可用，请联系管理员");
        }
        String displayName =
                request.displayName() == null || request.displayName().isBlank()
                        ? username
                        : request.displayName().trim();
        User user = User.registered(username, encoder.encode(request.password()), displayName);
        try {
            users.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }
        users.assignRegistrationRole(user.getId(), roleId);
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
        if (u == null
                || !"enable".equalsIgnoreCase(u.getStatus())
                || !Integer.valueOf(0).equals(u.getDeleted())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "账号不可用，请重新登录或联系管理员");
        }
        return issue(u);
    }

    boolean registrationEnabled() {
        return registrationEnabled;
    }

    boolean demoEnabled() {
        return demoEnabled;
    }

    @Transactional
    void logout(String token) {
        var authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof OpsPrincipal actor
                && actor.roles().contains("DEMO")) users.revokeVisitor(actor.userId());
        if (token != null && !token.isBlank()) refreshTokens.revoke(hash(token));
    }

    CurrentUser current() {
        OpsPrincipal p = SecurityUsers.current();
        if (p.roles().contains("DEMO")) {
            if (!actor(p.userId()).active())
                throw new BusinessException(ErrorCode.UNAUTHENTICATED, "访客会话已过期，请重新登录");
            return new CurrentUser(
                    p.userId(),
                    p.username(),
                    p.roles(),
                    List.of("demo:read", "rag:chat", "demo:run", "automation:run"));
        }
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

    ActorView actor(long userId) {
        if (userId < 0) {
            UserMapper.VisitorLease visitor = users.visitor(userId);
            Instant expiry =
                    visitor == null ? Instant.EPOCH : visitor.expiresAt().toInstant(ZoneOffset.UTC);
            boolean active =
                    demoEnabled
                            && visitor != null
                            && !visitor.revoked()
                            && expiry.isAfter(Instant.now());
            return new ActorView(
                    active, userId, "访客", active ? List.of("DEMO") : List.of(), expiry);
        }
        User user = users.selectById(userId);
        boolean active =
                user != null
                        && "enable".equalsIgnoreCase(user.getStatus())
                        && Integer.valueOf(0).equals(user.getDeleted());
        return new ActorView(
                active,
                userId,
                active ? user.getUsername() : "",
                active ? users.roles(userId) : List.of(),
                null);
    }

    record ActorView(
            boolean active, long userId, String username, List<String> roles, Instant expiresAt) {}

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
