package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.mq.MqNames;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费工单领域事件，并将其转换成可查询的平台操作审计。
 *
 * @author heyu
 * @since 2026/9/2
 */
@Component
public class TicketAuditConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(TicketAuditConsumer.class);
    private final ObjectMapper json;
    private final PlatformAuditService service;
    private final Counter success;
    private final Counter failure;

    TicketAuditConsumer(
            ObjectMapper json, PlatformAuditService service, MeterRegistry registry) {
        this.json = json;
        this.service = service;
        success = registry.counter("opsagent_mq_consume_success");
        failure = registry.counter("opsagent_mq_consume_failure");
    }

    @RabbitListener(queues = MqNames.PLATFORM_AUDIT_QUEUE)
    public void consume(Message message) {
        try {
            JsonNode event = json.readTree(message.getBody());
            if (service.record(event)) success.increment();
        } catch (Exception exception) {
            failure.increment();
            LOG.warn("工单审计事件消费失败，将按监听器策略重试", exception);
            throw new IllegalStateException("工单审计事件消费失败", exception);
        }
    }
}
