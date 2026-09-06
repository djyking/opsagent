package com.opsagent.demo;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 固定隔离 broker/vhost/queue，真实 publisher confirm 与手工 ACK 构成通知投递证据。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class DemoNotificationBroker {
    static final String QUEUE = "opsagent.demo.notification.v1";
    static final String VHOST = "notifications";
    private final ConnectionFactory factory;
    private final DemoRedis receipts;
    private final ExecutorService callbacks = Executors.newFixedThreadPool(2);
    private final Map<String, CompletableFuture<Instant>> pending = new ConcurrentHashMap<>();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private Connection connection;
    private Channel publisher;
    private Channel consumer;
    private volatile boolean enabled = true;
    private volatile Instant lastDelivered;

    DemoNotificationBroker(
            DemoRedis receipts,
            @Value("${ops.demo.rabbitmq-host:demo-rabbitmq}") String host,
            @Value("${ops.demo.rabbitmq-password:}") String password) {
        this.receipts = receipts;
        factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(5672);
        factory.setVirtualHost(VHOST);
        factory.setUsername("opsagent_notification");
        factory.setPassword(password);
        factory.setConnectionTimeout(1500);
        factory.setHandshakeTimeout(1500);
        factory.setChannelRpcTimeout(1500);
        factory.setShutdownTimeout(1000);
        factory.setRequestedHeartbeat(15);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setSharedExecutor(callbacks);
        factory.useNio();
        factory.setNioParams(
                new com.rabbitmq.client.impl.nio.NioParams()
                        .setNbIoThreads(1)
                        .setWriteQueueCapacity(64)
                        .setWriteEnqueuingTimeoutInMs(1000));
    }

    synchronized void maintain(boolean desiredEnabled) throws Exception {
        enabled = desiredEnabled;
        if (connection == null
                || !connection.isOpen()
                || publisher == null
                || !publisher.isOpen()) {
            closeConnection();
            connection = factory.newConnection("opsagent-isolated-notification");
            publisher = connection.createChannel();
            publisher.queueDeclare(
                    QUEUE,
                    true,
                    false,
                    false,
                    Map.of(
                            "x-max-length",
                            1000,
                            "x-max-length-bytes",
                            1048576,
                            "x-overflow",
                            "reject-publish"));
            publisher.confirmSelect();
        }
        if (!enabled && consumer != null) {
            // Closing only this consumer channel requeues any in-flight delivery without ACK.
            consumer.abort();
            consumer = null;
        }
        if (enabled && (consumer == null || !consumer.isOpen())) {
            Channel channel = connection.createChannel();
            channel.basicQos(1);
            consumer = channel;
            channel.basicConsume(
                    QUEUE,
                    false,
                    "opsagent-notification-worker",
                    (tag, message) -> {
                        if (!enabled || !channel.isOpen()) return;
                        String id = message.getProperties().getMessageId();
                        if (id == null
                                || !id.matches("[a-f0-9-]{36}")
                                || message.getBody().length > 512) {
                            channel.basicReject(message.getEnvelope().getDeliveryTag(), false);
                            return;
                        }
                        try {
                            // A Redis receipt with read-back confirmation precedes the RabbitMQ
                            // ACK.
                            if (!receipts.deliverNotification(
                                    id, new String(message.getBody(), StandardCharsets.UTF_8)))
                                throw new IllegalStateException("NOTIFICATION_RECEIPT_UNCONFIRMED");
                            if (!enabled || !channel.isOpen()) return;
                            channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
                        } catch (Exception failure) {
                            // Requeue through channel close; the maintenance interval bounds
                            // retries.
                            if (channel.isOpen()) channel.abort();
                            return;
                        }
                        Instant at = Instant.now();
                        lastDelivered = at;
                        delivered.incrementAndGet();
                        var completion = pending.get(id);
                        if (completion != null) completion.complete(at);
                    },
                    tag -> {});
        }
    }

    synchronized void publish(String id) throws Exception {
        if (publisher == null || !publisher.isOpen())
            throw new IllegalStateException("BROKER_UNAVAILABLE");
        publisher.basicPublish(
                "",
                QUEUE,
                true,
                new AMQP.BasicProperties.Builder()
                        .messageId(id)
                        .contentType("text/plain")
                        .deliveryMode(2)
                        .build(),
                ("OpsAgent notification " + id).getBytes(StandardCharsets.UTF_8));
        // Do not use waitForConfirmsOrDie: its implicit channel close can wait for a
        // broker that is blocking publishers. The explicit confirm deadline is enough.
        if (!publisher.waitForConfirms(1500))
            throw new IllegalStateException("PUBLICATION_REJECTED");
        published.incrementAndGet();
    }

    Delivery probe() {
        String id = UUID.randomUUID().toString();
        CompletableFuture<Instant> completion = new CompletableFuture<>();
        if (pending.size() >= 16) return new Delivery(false, id, null, "PROBE_BUSY");
        pending.put(id, completion);
        try {
            publish(id);
            Instant at = completion.get(1500, TimeUnit.MILLISECONDS);
            return new Delivery(true, id, at, "NOTIFICATION_DELIVERED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Delivery(false, id, null, "PROBE_INTERRUPTED");
        } catch (java.util.concurrent.TimeoutException exception) {
            return new Delivery(false, id, null, "NOTIFICATION_PENDING");
        } catch (Exception exception) {
            return new Delivery(false, id, null, "NOTIFICATION_BROKER_UNAVAILABLE");
        } finally {
            pending.remove(id);
        }
    }

    synchronized Map<String, Object> snapshot() throws Exception {
        if (publisher == null || !publisher.isOpen())
            throw new IllegalStateException("BROKER_UNAVAILABLE");
        var queue = publisher.queueDeclarePassive(QUEUE);
        return Map.of(
                "queue",
                QUEUE,
                "vhost",
                VHOST,
                "messagesReady",
                queue.getMessageCount(),
                "consumerCount",
                queue.getConsumerCount(),
                "publishedTotal",
                published.get(),
                "deliveredTotal",
                delivered.get(),
                "counterScope",
                "PROCESS_LIFETIME",
                "lastDeliveredAt",
                lastDelivered == null ? "" : lastDelivered.toString(),
                "observedAt",
                Instant.now().toString());
    }

    private void closeConnection() {
        if (connection != null) connection.abort(1000);
        consumer = null;
        publisher = null;
    }

    @PreDestroy
    synchronized void close() {
        enabled = false;
        closeConnection();
        callbacks.shutdownNow();
    }

    /**
     * @author heyu
     */
    record Delivery(boolean delivered, String messageId, Instant deliveredAt, String reasonCode) {}
}
