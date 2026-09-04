package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.mq.MqNames;
import com.rabbitmq.client.Channel;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 幂等消费索引事件，在业务成功后手动 ACK，并区分可重试与配置错误。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class DocumentIndexConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentIndexConsumer.class);
    private static final int MAXIMUM_ATTEMPTS = 10;
    private final KnowledgeIndexService indexService;
    private final KnowledgeRepository repository;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    DocumentIndexConsumer(
            KnowledgeIndexService indexService,
            KnowledgeRepository repository,
            ObjectMapper mapper,
            MeterRegistry metrics) {
        this.indexService = indexService;
        this.repository = repository;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @RabbitListener(queues = MqNames.DOCUMENT_INDEX_QUEUE)
    public void consume(Message message, Channel channel) {
        Timer.Sample sample = Timer.start(metrics);
        long taskId = 0;
        try {
            JsonNode event = mapper.readTree(message.getBody());
            String eventId = event.path("eventId").asText();
            JsonNode payload = event.path("payload");
            long documentId = payload.path("documentId").asLong();
            taskId = payload.path("taskId").asLong();
            int version = payload.path("documentVersion").asInt();
            if (repository.consumed("knowledge-document-indexer", eventId)) {
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }
            if (!repository.validDocumentVersion(documentId, version)) {
                throw new AmqpRejectAndDontRequeueException("文档版本已失效");
            }
            indexService.indexDocument(documentId);
            if (repository.consumeOnce("knowledge-document-indexer", eventId) == 1) {
                repository.indexTaskSuccess(taskId);
            }
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            metrics.counter("rag.index.consumer", "status", "success").increment();
        } catch (AmqpRejectAndDontRequeueException exception) {
            fail(taskId, exception);
            metrics.counter("rag.index.dlq").increment();
            throw exception;
        } catch (Exception exception) {
            fail(taskId, exception);
            if (nonRetryable(exception)) {
                metrics.counter("rag.index.dlq").increment();
                throw new AmqpRejectAndDontRequeueException(
                        "知识索引配置或数据不可重试", exception);
            }
            metrics.counter("rag.index.retry").increment();
            LOG.warn("知识索引消费失败，taskId={}，将按监听器策略重试", taskId);
            throw new IllegalStateException("知识索引失败", exception);
        } finally {
            sample.stop(metrics.timer("rag.index.duration"));
        }
    }

    private void fail(long taskId, Exception exception) {
        if (taskId > 0) {
            repository.indexTaskFailure(taskId, exception.getMessage(), MAXIMUM_ATTEMPTS);
        }
    }

    private boolean nonRetryable(Exception exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        return message.contains("维度不匹配")
                || message.contains("Mapping")
                || message.contains("payload")
                || message.contains("待索引文档不存在");
    }
}
