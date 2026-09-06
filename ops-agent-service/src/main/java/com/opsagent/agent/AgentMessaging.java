package com.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.mq.MqNames;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 至少一次通知只负责唤醒；数据库是运行与触发事实源，重复投递不重建执行。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Configuration
class AgentMessaging {
    private static final String EXCHANGE = "ops.agent.exchange";
    private final AgentStore store;
    private final AgentService service;
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;

    AgentMessaging(
            AgentStore store, AgentService service, JdbcTemplate jdbc, RabbitTemplate rabbit) {
        this.store = store;
        this.service = service;
        this.jdbc = jdbc;
        this.rabbit = rabbit;
    }

    @Bean
    Declarables agentQueues() {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        TopicExchange ticket = new TopicExchange(MqNames.TICKET_EXCHANGE, true, false);
        TopicExchange dead = new TopicExchange("ops.agent.dlx", true, false);
        Queue dlq = QueueBuilder.durable("ops.agent.dlq").build();
        Queue wake =
                QueueBuilder.durable("ops.agent.wake.queue")
                        .deadLetterExchange(dead.getName())
                        .deadLetterRoutingKey("dead")
                        .build();
        Queue alerts =
                QueueBuilder.durable("ops.agent.alert.queue")
                        .deadLetterExchange(dead.getName())
                        .deadLetterRoutingKey("dead")
                        .build();
        return new Declarables(
                exchange,
                ticket,
                dead,
                dlq,
                wake,
                alerts,
                BindingBuilder.bind(wake).to(exchange).with("run.wake"),
                BindingBuilder.bind(alerts).to(ticket).with("ticket.alert.created"),
                BindingBuilder.bind(dlq).to(dead).with("dead"));
    }

    @RabbitListener(queues = "ops.agent.wake.queue")
    void wake(Message message) {
        store.notifyRun(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    @RabbitListener(queues = "ops.agent.alert.queue")
    void alert(Message message) {
        if (message.getBody().length > 64000) throw AgentJson.invalid("告警事件过大");
        JsonNode body = AgentJson.read(new String(message.getBody(), StandardCharsets.UTF_8));
        JsonNode payload = body.path("payload");
        if (!payload.path("environment").asText().equals("ISOLATED")
                || payload.path("ownerActorId").asLong() == 0) return;
        String key = payload.path("episodeId").asText();
        if (!key.matches("[a-f0-9]{64}")) throw AgentJson.invalid("告警episode无效");
        jdbc.update(
                "INSERT IGNORE INTO agent_trigger(id,payload_json) VALUES(?,?)",
                key,
                payload.toString());
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    void dispatchTriggers() {
        jdbc.update(
                "UPDATE agent_trigger SET status='EXPIRED'"
                        + " WHERE status='PENDING' AND created_at<TIMESTAMPADD(MINUTE,-15,NOW(3))");
        for (Map<String, Object> row :
                jdbc.queryForList(
                        """
                        SELECT id,payload_json FROM agent_trigger
                        WHERE status='PENDING' AND next_attempt<=NOW(3) LIMIT 4
                        """)) {
            String id = row.get("id").toString();
            try {
                service.automatic(AgentJson.read(row.get("payload_json").toString()));
                jdbc.update(
                        "UPDATE agent_trigger SET status='DISPATCHED',last_error=NULL WHERE id=?",
                        id);
            } catch (Exception exception) {
                String message =
                        exception.getMessage() == null ? "自动触发暂不可用" : exception.getMessage();
                jdbc.update(
                        """
                        UPDATE agent_trigger SET last_error=?,next_attempt=TIMESTAMPADD(SECOND,30,NOW(3)) WHERE id=?
                        """,
                        message.substring(0, Math.min(message.length(), 350)),
                        id);
            }
        }
    }

    @Scheduled(fixedDelay = 1500, initialDelay = 10000)
    void publish() {
        for (Map<String, Object> row : store.outbox()) {
            String id = row.get("id").toString();
            try {
                MessageProperties properties = new MessageProperties();
                properties.setMessageId(id);
                properties.setDeliveryMode(
                        org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                CorrelationData confirmation = new CorrelationData(UUID.randomUUID().toString());
                rabbit.send(
                        EXCHANGE,
                        "run.wake",
                        new Message(
                                row.get("run_id").toString().getBytes(StandardCharsets.UTF_8),
                                properties),
                        confirmation);
                if (confirmation.getFuture().get(4, TimeUnit.SECONDS).isAck()) store.sent(id);
            } catch (Exception exception) {
                return;
            }
        }
    }
}
