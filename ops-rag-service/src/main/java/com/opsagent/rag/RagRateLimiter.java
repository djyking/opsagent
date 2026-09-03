package com.opsagent.rag;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 为单实例开发环境提供用户级每分钟问答限流，防止意外消耗模型额度。
 *
 * @author heyu
 * @since 2026/9/2
 */
@Component
public class RagRateLimiter {
    private static final long WINDOW_SECONDS = 60L;
    private final RagProperties properties;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    RagRateLimiter(RagProperties properties) {
        this.properties = properties;
    }

    void check() {
        long userId = SecurityUsers.current().userId();
        long now = Instant.now().getEpochSecond();
        Window result = windows.compute(userId, (key, current) -> {
            if (current == null || now - current.startedAt() >= WINDOW_SECONDS) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt(), current.count() + 1);
        });
        if (result.count() > Math.max(1, properties.getRequestsPerMinute())) {
            throw new BusinessException(ErrorCode.VALIDATION, "问答请求过于频繁，请稍后再试");
        }
    }

    /**
     * 保存单个用户当前固定时间窗口的开始时间和请求数。
     *
     * @author heyu
     * @since 2026/9/2
     */
    private record Window(long startedAt, int count) {}
}
