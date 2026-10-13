package com.opsagent.auth;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import java.time.Duration;
import java.time.Instant;

/**
 * 活动续期只能延长空闲期限，不能突破首次登录的绝对期限。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class SessionLifetime {
    private SessionLifetime() {}

    static Instant activity(
            Instant previous,
            Instant proposed,
            Instant absolute,
            Instant now,
            Duration idleTimeout) {
        if (!now.isBefore(absolute)) throw expired("本次登录已满 24 小时，请重新登录");
        if (!now.isBefore(previous.plus(idleTimeout))) throw expired("超过 2 小时未操作，请重新登录");
        if (proposed == null || !proposed.isAfter(previous)) return previous;
        if (proposed.isAfter(now.plusSeconds(5)))
            throw new BusinessException(ErrorCode.VALIDATION, "活动时间无效，请检查设备时间");
        return proposed.isAfter(now) ? now : proposed;
    }

    static Instant deadline(Instant activity, Instant absolute, Duration idleTimeout) {
        Instant idle = activity.plus(idleTimeout);
        return idle.isBefore(absolute) ? idle : absolute;
    }

    private static BusinessException expired(String message) {
        return new BusinessException(ErrorCode.UNAUTHENTICATED, message);
    }
}
