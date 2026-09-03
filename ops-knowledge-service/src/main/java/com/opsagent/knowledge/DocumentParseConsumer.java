package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.mq.MqNames;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费文档解析任务，提供消费幂等、失败重试和可观测指标。
 *
 * @author heyu
 * @since 2026/8/24
 */
@Component
public class DocumentParseConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentParseConsumer.class);
    private static final int MAXIMUM_ATTEMPTS = 3;
    private final KnowledgeService service;
    private final KnowledgeRepository repository;
    private final ObjectMapper json;
    private final Counter success;
    private final Counter failure;
    private final Timer duration;

    DocumentParseConsumer(
            KnowledgeService service,
            KnowledgeRepository repository,
            ObjectMapper json,
            MeterRegistry registry) {
        this.service = service;
        this.repository = repository;
        this.json = json;
        success = registry.counter("opsagent_document_parse_success");
        failure = registry.counter("opsagent_document_parse_failure");
        duration = registry.timer("opsagent_document_parse_duration");
    }

    @RabbitListener(queues = MqNames.DOCUMENT_PARSE_QUEUE)
    public void consume(Message message) {
        Timer.Sample sample = Timer.start();
        long documentId = 0;
        long taskId = 0;
        try {
            JsonNode event = json.readTree(message.getBody());
            documentId = event.path("payload").path("documentId").asLong();
            taskId = event.path("payload").path("taskId").asLong();
            KnowledgeService.ParsedDocument parsed = service.parseFile(documentId);
            if (service.completeParse(event.path("eventId").asText(), taskId, parsed)) {
                success.increment();
            }
        } catch (Exception exception) {
            failure.increment();
            if (taskId > 0) {
                repository.taskAttemptFailed(
                        taskId, documentId, exception.getMessage(), MAXIMUM_ATTEMPTS);
            }
            LOG.warn("文档解析消费失败，taskId={}，将按监听器策略重试", taskId, exception);
            throw new IllegalStateException("文档解析失败", exception);
        } finally {
            sample.stop(duration);
        }
    }
}
