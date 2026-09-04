package com.opsagent.knowledge;

import com.opsagent.common.mq.MqNames;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 声明文档解析主队列、死信队列及消息 JSON 转换规则。
 *
 * @author heyu
 * @since 2026/8/21
 */
@Configuration
public class KnowledgeMqConfiguration {
    @Bean
    DirectExchange knowledgeExchange() {
        return new DirectExchange(MqNames.KNOWLEDGE_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange documentParseDeadLetterExchange() {
        return new DirectExchange(MqNames.DOCUMENT_PARSE_DLX, true, false);
    }

    @Bean
    DirectExchange documentIndexDeadLetterExchange() {
        return new DirectExchange(MqNames.DOCUMENT_INDEX_DLX, true, false);
    }

    @Bean
    Queue documentParseQueue() {
        return new Queue(
                MqNames.DOCUMENT_PARSE_QUEUE,
                true,
                false,
                false,
                Map.of(
                        "x-dead-letter-exchange",
                        MqNames.DOCUMENT_PARSE_DLX,
                        "x-dead-letter-routing-key",
                        MqNames.DOCUMENT_PARSE_DEAD_ROUTING_KEY));
    }

    @Bean
    Queue documentParseDeadLetterQueue() {
        return new Queue(MqNames.DOCUMENT_PARSE_DLQ, true);
    }

    @Bean
    Queue documentIndexQueue() {
        return new Queue(
                MqNames.DOCUMENT_INDEX_QUEUE,
                true,
                false,
                false,
                Map.of(
                        "x-dead-letter-exchange",
                        MqNames.DOCUMENT_INDEX_DLX,
                        "x-dead-letter-routing-key",
                        MqNames.DOCUMENT_INDEX_DEAD_ROUTING_KEY));
    }

    @Bean
    Queue documentIndexDeadLetterQueue() {
        return new Queue(MqNames.DOCUMENT_INDEX_DLQ, true);
    }

    @Bean
    Binding documentParseBinding(Queue documentParseQueue, DirectExchange knowledgeExchange) {
        return BindingBuilder.bind(documentParseQueue)
                .to(knowledgeExchange)
                .with(MqNames.DOCUMENT_PARSE_ROUTING_KEY);
    }

    @Bean
    Binding documentParseDeadLetterBinding(
            Queue documentParseDeadLetterQueue, DirectExchange documentParseDeadLetterExchange) {
        return BindingBuilder.bind(documentParseDeadLetterQueue)
                .to(documentParseDeadLetterExchange)
                .with(MqNames.DOCUMENT_PARSE_DEAD_ROUTING_KEY);
    }

    @Bean
    Binding documentIndexBinding(Queue documentIndexQueue, DirectExchange knowledgeExchange) {
        return BindingBuilder.bind(documentIndexQueue)
                .to(knowledgeExchange)
                .with(MqNames.DOCUMENT_INDEX_ROUTING_KEY);
    }

    @Bean
    Binding documentIndexDeadLetterBinding(
            Queue documentIndexDeadLetterQueue, DirectExchange documentIndexDeadLetterExchange) {
        return BindingBuilder.bind(documentIndexDeadLetterQueue)
                .to(documentIndexDeadLetterExchange)
                .with(MqNames.DOCUMENT_INDEX_DEAD_ROUTING_KEY);
    }

}
