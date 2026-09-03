package com.opsagent.ticket;

import com.opsagent.common.mq.MqNames;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

/**
 * 配置工单事件交换机、JSON 消息格式和 Outbox 定时发布能力。
 *
 * @author heyu
 * @since 2026/8/13
 */
@Configuration
@EnableScheduling
public class TicketMqConfiguration {
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

    @Bean
    MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
