package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.mq.DocumentParseRequested;
import com.opsagent.common.mq.DomainEvent;
import com.opsagent.common.mq.MqNames;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 创建文档解析领域事件并等待 RabbitMQ 发布确认。
 *
 * @author heyu
 * @since 2026/8/22
 */
@Component
public class DocumentParsePublisher {
    private final RabbitTemplate rabbit;
    private final KnowledgeRepository repository;
    private final ObjectMapper json;
    private final Counter queued;

    @Value("${ops.mq.document-parse.confirm-timeout-seconds:5}")
    private long confirmTimeoutSeconds;

    DocumentParsePublisher(
            RabbitTemplate rabbit,
            KnowledgeRepository repository,
            ObjectMapper json,
            MeterRegistry registry) {
        this.rabbit = rabbit;
        this.repository = repository;
        this.json = json;
        queued = registry.counter("opsagent_document_parse_queue");
    }

    void publish(long documentId, long taskId) {
        String eventId = UUID.randomUUID().toString();
        DomainEvent<DocumentParseRequested> event =
                new DomainEvent<>(
                        eventId,
                        MqNames.DOCUMENT_PARSE_ROUTING_KEY,
                        Instant.now(),
                        new DocumentParseRequested(documentId, taskId));
        try {
            CorrelationData correlation = new CorrelationData(eventId);
            rabbit.convertAndSend(
                    MqNames.KNOWLEDGE_EXCHANGE,
                    MqNames.DOCUMENT_PARSE_ROUTING_KEY,
                    json.writeValueAsBytes(event),
                    correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeoutSeconds, TimeUnit.SECONDS);
            if (!confirm.isAck()) throw new IllegalStateException(confirm.getReason());
            queued.increment();
        } catch (Exception exception) {
            repository.taskPublishFailed(taskId, exception.getMessage());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "解析任务入队失败，请稍后重试");
        }
    }
}
