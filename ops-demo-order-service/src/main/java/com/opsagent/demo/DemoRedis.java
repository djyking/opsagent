package com.opsagent.demo;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.DefaultClientResources;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 单连接真实 Redis 查询，仅能访问部署时固定的独立演示 Redis。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class DemoRedis {
    private final String host;
    private final String password;
    private final DefaultClientResources resources =
            DefaultClientResources.builder()
                    .ioThreadPoolSize(2)
                    .computationThreadPoolSize(2)
                    .build();
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private int connectedPort;
    private RedisClient notificationClient;
    private StatefulRedisConnection<String, String> notificationConnection;

    DemoRedis(
            @Value("${ops.demo.redis-host}") String host,
            @Value("${ops.demo.redis-password}") String password) {
        this.host = host;
        this.password = password;
    }

    synchronized String catalog(int port) {
        if (port != 6379 && port != 6380) throw new IllegalArgumentException("PORT_NOT_ALLOWED");
        if (connection == null || connectedPort != port || !connection.isOpen()) {
            closeConnection();
            RedisURI.Builder uri =
                    RedisURI.Builder.redis(host, port).withTimeout(Duration.ofMillis(500));
            if (!password.isBlank()) uri.withPassword(password.toCharArray());
            client = RedisClient.create(resources, uri.build());
            connection = client.connect();
            connectedPort = port;
        }
        return connection.sync().get("demo:order:catalog:v1");
    }

    synchronized void seed() {
        catalog(6379);
        connection.sync().setnx("demo:order:catalog:v1", "OpsAgent demo order catalog");
    }

    synchronized boolean deliverNotification(String id, String content) {
        if (!id.matches("[a-f0-9-]{36}") || content.length() > 512)
            throw new IllegalArgumentException("INVALID_NOTIFICATION");
        if (notificationConnection == null || !notificationConnection.isOpen()) {
            if (notificationConnection != null) notificationConnection.close();
            if (notificationClient != null)
                notificationClient.shutdown(Duration.ZERO, Duration.ofMillis(200));
            RedisURI.Builder uri =
                    RedisURI.Builder.redis(host, 6379).withTimeout(Duration.ofMillis(500));
            if (!password.isBlank()) uri.withPassword(password.toCharArray());
            notificationClient = RedisClient.create(resources, uri.build());
            notificationConnection = notificationClient.connect();
        }
        String key = "demo:notification:receipt:" + id;
        notificationConnection.sync().setex(key, 600, content);
        return content.equals(notificationConnection.sync().get(key));
    }

    private void closeConnection() {
        if (connection != null) connection.close();
        if (client != null) client.shutdown(Duration.ZERO, Duration.ofMillis(200));
        connection = null;
        client = null;
    }

    @PreDestroy
    void shutdown() {
        closeConnection();
        if (notificationConnection != null) notificationConnection.close();
        if (notificationClient != null)
            notificationClient.shutdown(Duration.ZERO, Duration.ofMillis(200));
        resources.shutdown();
    }
}
