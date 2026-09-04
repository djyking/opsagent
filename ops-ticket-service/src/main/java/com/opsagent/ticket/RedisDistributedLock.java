package com.opsagent.ticket;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 使用 SET NX PX 和 compare-delete Lua 脚本提供最小分布式锁。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class RedisDistributedLock {
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] "
                    + "then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class);
    private final StringRedisTemplate redis;

    RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        try {
            redis.execute(UNLOCK, Collections.singletonList(key), token);
        } catch (RuntimeException ignored) {
            // 锁有 TTL，Redis 短暂不可用时允许其自动过期。
        }
    }
}
