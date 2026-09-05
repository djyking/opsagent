package com.opsagent.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 仅测试源码中的 Redis 替身，保留生产服务的摘要和一次性验证流程。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class CaptchaRedisFixture {
    final Map<String, String> stored = new ConcurrentHashMap<>();
    final Map<String, Duration> ttl = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    CaptchaRedisFixture(StringRedisTemplate redis) {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(invocation -> {
            stored.put(invocation.getArgument(0), invocation.getArgument(1));
            ttl.put(invocation.getArgument(0), invocation.getArgument(2));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), anyString(), anyString())).thenReturn(1L);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(), anyList()))
                .thenAnswer(invocation -> stored.remove(((java.util.List<?>) invocation.getArgument(1)).get(0)));
    }
}
