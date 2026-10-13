package com.opsagent.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.mq.DomainEvent;
import com.opsagent.common.mq.MqNames;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * 周期扫描工单 Outbox，并在发布确认成功后更新事件状态。
 *
 * @author heyu
 * @since 2026/8/14
 */
@Component
@ConditionalOnProperty(name = "ops.mq.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxMapper outbox;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final Counter success;
    private final Counter failure;

    @Value("${ops.mq.outbox.batch-size:50}")
    private int batchSize;

    @Value("${ops.mq.outbox.confirm-timeout-seconds:5}")
    private long confirmTimeoutSeconds;

    @Value("${ops.mq.outbox.stale-publishing-seconds:30}")
    private long stalePublishingSeconds;

    OutboxPublisher(
            OutboxMapper outbox,
            RabbitTemplate rabbit,
            ObjectMapper json,
            MeterRegistry registry) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.json = json;
        success = registry.counter("opsagent_outbox_publish_success");
        failure = registry.counter("opsagent_outbox_publish_failure");
        registry.gauge("opsagent_outbox_pending", outbox, this::pendingCount);
    }

    @Scheduled(
            fixedDelayString = "${ops.mq.outbox.fixed-delay-ms:2000}",
            initialDelayString = "${ops.mq.outbox.initial-delay-ms:3000}")
    void publishPending() {
        int recovered = outbox.recoverStalePublishing(stalePublishingSeconds);
        if (recovered > 0) LOG.warn("已回收 {} 条超时的 PUBLISHING 事件", recovered);
        for (OutboxMapper.OutboxEvent event : outbox.pending(batchSize)) publish(event);
    }

    private void publish(OutboxMapper.OutboxEvent event) {
        if (outbox.claim(event.id()) != 1) return;
        try {
            JsonNode payload = json.readTree(event.payload());
            DomainEvent<JsonNode> message =
                    new DomainEvent<>(
                            event.eventId(),
                            event.eventType(),
                            event.createTime().atZone(ZoneId.systemDefault()).toInstant(),
                            payload);
            CorrelationData correlation = new CorrelationData(event.eventId());
            rabbit.convertAndSend(MqNames.TICKET_EXCHANGE, event.eventType(), message, correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeoutSeconds, TimeUnit.SECONDS);
            if (!confirm.isAck()) throw new IllegalStateException(confirm.getReason());
            outbox.sent(event.id());
            success.increment();
        } catch (Exception exception) {
            outbox.failed(event.id());
            failure.increment();
            LOG.warn("工单事件发布失败，eventId={}，稍后重试", event.eventId(), exception);
        }
    }

    private double pendingCount(OutboxMapper mapper) {
        return mapper.pending(1000).size();
    }
}
