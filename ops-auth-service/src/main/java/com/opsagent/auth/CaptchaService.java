package com.opsagent.auth;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 发放限时验证码并在校验时原子消费；Redis 不可用时拒绝发放和校验。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class CaptchaService {
    private static final Duration TTL = Duration.ofSeconds(120);
    private static final String KEY_PREFIX = "opsagent:auth:captcha:";
    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if value then redis.call('DEL', KEYS[1]) end
            return value
            """, String.class);
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local peer = tonumber(redis.call('GET', KEYS[1]) or '0')
            local total = tonumber(redis.call('GET', KEYS[2]) or '0')
            if peer >= tonumber(ARGV[1]) or total >= tonumber(ARGV[2]) then return 0 end
            if redis.call('INCR', KEYS[1]) == 1 then redis.call('EXPIRE', KEYS[1], 60) end
            if redis.call('INCR', KEYS[2]) == 1 then redis.call('EXPIRE', KEYS[2], 60) end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final CaptchaImageGenerator generator;
    private final SecureRandom random = new SecureRandom();
    private final int peerLimit;
    private final int globalLimit;

    CaptchaService(StringRedisTemplate redis, CaptchaImageGenerator generator,
            @Value("${ops.auth.captcha.peer-limit-per-minute:60}") int peerLimit,
            @Value("${ops.auth.captcha.global-limit-per-minute:1000}") int globalLimit) {
        this.redis = redis;
        this.generator = generator;
        this.peerLimit = Math.max(1, peerLimit);
        this.globalLimit = Math.max(1, globalLimit);
    }

    AuthDtos.CaptchaResponse issue(String remoteAddress) {
        try {
            // 使用真实连接来源，不信任任意调用方提供的代理头；网关后的连接共享来源额度。
            String peerKey = KEY_PREFIX + "{issue}:peer:" + digest(String.valueOf(remoteAddress));
            Long permitted = redis.execute(LIMIT, List.of(peerKey, KEY_PREFIX + "{issue}:global"),
                    Integer.toString(peerLimit), Integer.toString(globalLimit));
            if (!Long.valueOf(1).equals(permitted)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "验证码刷新过于频繁，请稍后重试");
            }
            byte[] idBytes = new byte[16];
            random.nextBytes(idBytes);
            String id = HexFormat.of().formatHex(idBytes);
            CaptchaImageGenerator.Generated generated = generator.generate();
            redis.opsForValue().set(KEY_PREFIX + id, digest(id + ":" + generated.answer()), TTL);
            return new AuthDtos.CaptchaResponse(id, generated.imageDataUrl(), TTL.toSeconds());
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    void verify(String captchaId, String captchaCode) {
        if (captchaId == null || !captchaId.matches("[a-f0-9]{32}")) {
            throw invalid();
        }
        String expected;
        try {
            expected = redis.execute(CONSUME, List.of(KEY_PREFIX + captchaId));
        } catch (DataAccessException exception) {
            throw unavailable();
        }
        String submitted = captchaCode == null ? "" : captchaCode.trim().toUpperCase(Locale.ROOT);
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                digest(captchaId + ":" + submitted).getBytes(StandardCharsets.UTF_8))) {
            throw invalid();
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION, "验证码错误或已过期，请换一张后重试");
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "验证码服务暂不可用，请稍后重试");
    }
}
