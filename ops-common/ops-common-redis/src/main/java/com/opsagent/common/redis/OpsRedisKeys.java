package com.opsagent.common.redis;

/** 集中维护 Redis Key 命名规则，防止不同服务之间发生键冲突。 */
public final class OpsRedisKeys {
    private OpsRedisKeys() {}

    public static String refresh(long userId, String tokenId) {
        return "ops:auth:refresh:" + userId + ":" + tokenId;
    }

    public static String permission(long userId) {
        return "ops:auth:permission:" + userId;
    }

    public static String idempotent(String key) {
        return "ops:idempotent:" + key;
    }

    public static String mq(String consumer, String eventId) {
        return "ops:mq:" + consumer + ":" + eventId;
    }

    public static String documentLock(long id) {
        return "ops:lock:document:" + id;
    }
}
