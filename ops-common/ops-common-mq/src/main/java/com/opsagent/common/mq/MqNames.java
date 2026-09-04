package com.opsagent.common.mq;

/**
 * 集中定义跨服务 RabbitMQ 交换机、队列与路由键名称。
 *
 * @author heyu
 * @since 2026/8/1
 */
public final class MqNames {
    public static final String TICKET_EXCHANGE = "ops.ticket.exchange";
    public static final String PLATFORM_AUDIT_QUEUE = "ops.platform.audit.queue";
    public static final String PLATFORM_AUDIT_ROUTING_PATTERN = "ticket.#";
    public static final String PLATFORM_AUDIT_DLX = "ops.platform.audit.dlx";
    public static final String PLATFORM_AUDIT_DLQ = "ops.platform.audit.dlq";
    public static final String PLATFORM_AUDIT_DEAD_ROUTING_KEY = "platform.audit.dead";

    public static final String KNOWLEDGE_EXCHANGE = "ops.knowledge.exchange";
    public static final String DOCUMENT_PARSE_ROUTING_KEY = "knowledge.document.parse.requested";
    public static final String DOCUMENT_PARSE_QUEUE = "ops.knowledge.parse.queue";
    public static final String DOCUMENT_PARSE_DLX = "ops.knowledge.parse.dlx";
    public static final String DOCUMENT_PARSE_DLQ = "ops.knowledge.parse.dlq";
    public static final String DOCUMENT_PARSE_DEAD_ROUTING_KEY = "knowledge.document.parse.dead";
    public static final String DOCUMENT_INDEX_ROUTING_KEY = "knowledge.document.index.requested";
    public static final String DOCUMENT_INDEX_QUEUE = "ops.knowledge.index.queue";
    public static final String DOCUMENT_INDEX_DLX = "ops.knowledge.index.dlx";
    public static final String DOCUMENT_INDEX_DLQ = "ops.knowledge.index.dlq";
    public static final String DOCUMENT_INDEX_DEAD_ROUTING_KEY = "knowledge.document.index.dead";

    private MqNames() {}
}
