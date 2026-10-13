package com.opsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 消费独立 SLA 通知队列，失败由已有有限重试和死信策略保留。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class SlaNotificationConsumer {
    private final ObjectMapper json;
    private final SlaNotificationService service;

    SlaNotificationConsumer(ObjectMapper json, SlaNotificationService service) {
        this.json = json;
        this.service = service;
    }

    @RabbitListener(queues = "opsagent.platform.sla.notifications")
    public void consume(Message message) throws IOException {
        service.record(json.readTree(message.getBody()));
    }
}
