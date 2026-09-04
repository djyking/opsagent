package com.opsagent.knowledge;

import com.opsagent.common.mq.MqNames;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 从知识服务本地 Outbox 可靠发布索引事件并等待 RabbitMQ Confirm。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class KnowledgeOutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeOutboxPublisher.class);
    private final KnowledgeRepository repository;
    private final RabbitTemplate rabbit;
    private final MeterRegistry metrics;

    @Value("${ops.mq.document-index.confirm-timeout-seconds:5}")
    private long confirmTimeoutSeconds;

    KnowledgeOutboxPublisher(
            KnowledgeRepository repository,
            RabbitTemplate rabbit,
            MeterRegistry metrics) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${ops.knowledge.outbox-publish-delay-ms:1000}")
    void publishDue() {
        for (Map<String, Object> event : repository.dueOutboxEvents(50)) {
            publish(event);
        }
    }

    void publish(Map<String, Object> event) {
        long id = number(event, "id");
        if (repository.claimOutbox(id) == 0) {
            return;
        }
        String eventId = text(event, "event_id", "eventId");
        try {
            CorrelationData correlation = new CorrelationData(eventId);
            rabbit.convertAndSend(
                    MqNames.KNOWLEDGE_EXCHANGE,
                    text(event, "event_type", "eventType"),
                    text(event, "payload").getBytes(StandardCharsets.UTF_8),
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutSeconds, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException(confirm.getReason());
            }
            repository.outboxSent(id);
            metrics.counter("rag.index.outbox", "status", "sent").increment();
        } catch (Exception exception) {
            repository.outboxFailed(id, exception.getMessage());
            metrics.counter("rag.index.outbox", "status", "retry").increment();
            LOG.warn("知识索引 Outbox 发布失败，eventId={}，等待退避重试", eventId);
        }
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String text(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }
}
