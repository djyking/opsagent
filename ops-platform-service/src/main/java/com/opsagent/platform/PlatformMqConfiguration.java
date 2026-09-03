package com.opsagent.platform;

import com.opsagent.common.mq.MqNames;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 声明平台审计消费队列以及不可恢复消息的死信队列。
 *
 * @author heyu
 * @since 2026/8/29
 */
@Configuration
public class PlatformMqConfiguration {
    @Bean
    TopicExchange ticketExchange() {
        return new TopicExchange(MqNames.TICKET_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange platformAuditDeadLetterExchange() {
        return new DirectExchange(MqNames.PLATFORM_AUDIT_DLX, true, false);
    }

    @Bean
    Queue platformAuditQueue() {
        return new Queue(
                MqNames.PLATFORM_AUDIT_QUEUE,
                true,
                false,
                false,
                Map.of(
                        "x-dead-letter-exchange",
                        MqNames.PLATFORM_AUDIT_DLX,
                        "x-dead-letter-routing-key",
                        MqNames.PLATFORM_AUDIT_DEAD_ROUTING_KEY));
    }

    @Bean
    Queue platformAuditDeadLetterQueue() {
        return new Queue(MqNames.PLATFORM_AUDIT_DLQ, true);
    }

    @Bean
    Binding platformAuditBinding(Queue platformAuditQueue, TopicExchange ticketExchange) {
        return BindingBuilder.bind(platformAuditQueue)
                .to(ticketExchange)
                .with(MqNames.PLATFORM_AUDIT_ROUTING_PATTERN);
    }

    @Bean
    Binding platformAuditDeadLetterBinding(
            Queue platformAuditDeadLetterQueue,
            DirectExchange platformAuditDeadLetterExchange) {
        return BindingBuilder.bind(platformAuditDeadLetterQueue)
                .to(platformAuditDeadLetterExchange)
                .with(MqNames.PLATFORM_AUDIT_DEAD_ROUTING_KEY);
    }
}
